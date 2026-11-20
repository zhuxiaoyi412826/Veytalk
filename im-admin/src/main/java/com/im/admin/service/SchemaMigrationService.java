package com.im.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.admin.config.AdminProperties;
import com.im.admin.dto.vo.SchemaLogVO;
import com.im.admin.dto.vo.SchemaOverviewVO;
import com.im.admin.dto.vo.SchemaScriptVO;
import com.im.admin.dto.vo.SchemaVersionVO;
import com.im.admin.entity.SchemaMigration;
import com.im.admin.entity.SchemaMigrationLog;
import com.im.admin.mapper.SchemaMigrationLogMapper;
import com.im.admin.mapper.SchemaMigrationMapper;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SQL 版本管理：扫描 {@code sql/versions} 的 V/U 配对脚本，与 {@code im_schema_migration}
 * 台账合并出「库现在到底处于哪个版本」，并提供应用 / 一键回滚与操作流水。
 *
 * <p><b>为什么自研而不是 Flyway/Liquibase</b>：两个成熟工具的自动回滚都要付费版或 XML 改造，
 * 而本项目约定 MySQL 写操作一律由库管理员本人发起——工具「启动时自动 migrate」的模式恰好是
 * 这套闸门要避免的。自研版把执行入口收进后台按钮（人点的、带权限点、逐条落流水），
 * 目录命名仍兼容 Flyway 的 {@code V<版本>__<名>.sql}，将来换工具时脚本目录不用动。
 *
 * <p><b>回滚的诚实边界</b>：一键回滚 = 执行改动时就配套写好的 U 脚本，只能还原结构；
 * DROP/DELETE 掉的数据任何工具都变不回来，所以 U 脚本要求幂等（IF EXISTS），页面要求先备份。
 *
 * <p>并发控制只用了进程内 {@link ReentrantLock}：本服务单实例部署，跨节点的互斥是过度设计；
 * 但同一 JVM 里两个管理员同时点按钮是真实的，锁住整个 apply/rollback（含逐条执行脚本）即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaMigrationService {

    private final SchemaMigrationMapper migrationMapper;
    private final SchemaMigrationLogMapper logMapper;
    private final AdminProperties adminProperties;
    private final DataSource dataSource;

    /** 应用与回滚全程互斥（见类注释）；tryLock 失败直接拒，不做排队——排队等于允许按钮连点 */
    private final ReentrantLock changeLock = new ReentrantLock();

    private volatile JdbcTemplate jdbc;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** V1.0.4__xxx.sql：版本号允许数字与点之外的字母/连字符（如 1.0.4-rc1），比较时按段拆解 */
    private static final Pattern V_FILE = Pattern.compile("^V([0-9][0-9A-Za-z.\\-]*)__(.+)\\.sql$");
    private static final Pattern U_FILE = Pattern.compile("^U([0-9][0-9A-Za-z.\\-]*)__(.+)\\.sql$");
    private static final String BOOTSTRAP_HINT =
            "台账表不存在：请先由库管理员执行 sql/migration_schema_version.sql（台账 1.0.5 引导脚本），再刷新本页面";

    private JdbcTemplate jdbc() {
        JdbcTemplate it = jdbc;
        if (it == null) {
            synchronized (this) {
                if (jdbc == null) {
                    jdbc = new JdbcTemplate(dataSource);
                }
                it = jdbc;
            }
        }
        return it;
    }

    /* ==================== 读：整屏清单与流水 ==================== */

    /**
     * 台账行与目录文件合并后的整屏数据。两个前置条件（目录在不在、表建没建）都不抛错，
     * 以 {@code dirFound}/{@code tableReady} 标志返回——第一次进页面（还没跑引导脚本）
     * 是最典型的场景，要能看清原因而不是撞一个 500。
     */
    public SchemaOverviewVO overview() {
        Path dir = resolveDir(false);
        boolean dirFound = dir != null;
        List<SchemaVersionVO> items = new ArrayList<>();
        String latestApplied = null;
        // 表在不在与目录在不在互相独立：jar 部署不带 sql 目录时，台账行仍要能展示（只是没按钮）
        boolean tableReady = tableExists();
        Map<String, String> vFiles = dirFound ? scanFiles(dir, V_FILE) : Map.of();
        Map<String, String> uFiles = dirFound ? scanFiles(dir, U_FILE) : Map.of();
        Map<String, SchemaMigration> rows = tableReady ? loadRows() : Map.of();

        // 版本全集 = 台账行 ∪ 目录里的 V 文件，按版本号升序展示（人从下往上读时间线，从上往下点应用）
        TreeSet<String> allVersions = new TreeSet<>(this::compareVersion);
        allVersions.addAll(rows.keySet());
        allVersions.addAll(vFiles.keySet());

        String minApplicable = null;
        for (String v : allVersions) {
            SchemaMigration row = rows.get(v);
            boolean applicable = vFiles.containsKey(v)
                    && (row == null || row.getStatus() == SchemaMigration.STATUS_ROLLED_BACK
                        || row.getStatus() == SchemaMigration.STATUS_FAILED);
            if (applicable && minApplicable == null) {
                minApplicable = v;
            }
        }

        for (String v : allVersions) {
            SchemaMigration row = rows.get(v);
            String vFile = vFiles.get(v);
            String uFile = uFiles.get(v);
            boolean checksumMatched = row != null && row.getChecksum() != null && vFile != null
                    && row.getChecksum().equalsIgnoreCase(sha256Hex(dir.resolve(vFile)));
            boolean canApply = vFile != null && v.equals(minApplicable)
                    && (row == null || row.getStatus() == SchemaMigration.STATUS_ROLLED_BACK
                        || row.getStatus() == SchemaMigration.STATUS_FAILED);
            boolean canRollback = row != null && row.getStatus() == SchemaMigration.STATUS_APPLIED
                    && row.getUndoName() != null && uFile != null && checksumMatched;

            if (row != null && row.getStatus() == SchemaMigration.STATUS_APPLIED
                    && (latestApplied == null || compareVersion(v, latestApplied) > 0)) {
                latestApplied = v;
            }
            items.add(SchemaVersionVO.builder()
                    .version(v)
                    .scriptName(row != null ? row.getScriptName() : vFile)
                    .undoName(row != null ? row.getUndoName() : uFile)
                    .description(row != null ? row.getDescription() : null)
                    .status(row != null ? row.getStatus() : (vFile != null ? null : SchemaMigration.STATUS_APPLIED))
                    .statusText(statusText(row, vFile, checksumMatched))
                    .fileFound(vFile != null || (row != null && row.getChecksum() == null))
                    .checksumMatched(row != null && row.getChecksum() != null && vFile != null ? checksumMatched : null)
                    .operatorName(row != null ? row.getOperatorName() : null)
                    .appliedAt(row != null && row.getAppliedAt() != null ? row.getAppliedAt().format(TIME_FMT) : null)
                    .rolledBackAt(row != null && row.getRolledBackAt() != null ? row.getRolledBackAt().format(TIME_FMT) : null)
                    .canApply(canApply)
                    .canRollback(canRollback)
                    .build());
        }
        // LIFO 收口：只有「版本号最高的已应用行」整体可回滚，其余行即便配了 U 也不给按钮
        String head = latestApplied;
        items.forEach(it -> {
            if (!it.getVersion().equals(head)) {
                it.setCanRollback(false);
            }
        });
        return SchemaOverviewVO.builder()
                .dir(dirFound ? dir.toAbsolutePath().toString() : describeProbe())
                .dirFound(dirFound)
                .tableReady(tableReady)
                .latestApplied(latestApplied)
                .items(items)
                .build();
    }

    /** 变更流水倒序（时间线数据源）。表没建时返回空列表，页面显示提示条而不是报错 */
    public List<SchemaLogVO> logs(int limit) {
        if (!tableExists()) {
            return List.of();
        }
        int size = Math.clamp(limit, 1, 200);
        List<SchemaMigrationLog> rows = logMapper.selectList(new LambdaQueryWrapper<SchemaMigrationLog>()
                .orderByDesc(SchemaMigrationLog::getId)
                .last("LIMIT " + size));
        List<SchemaLogVO> out = new ArrayList<>(rows.size());
        for (SchemaMigrationLog r : rows) {
            out.add(SchemaLogVO.builder()
                    .version(r.getVersion())
                    .action(r.getAction())
                    .result(r.getResult())
                    .statementCount(r.getStatementCount())
                    .durationMs(r.getDurationMs())
                    .message(r.getMessage())
                    .operatorName(r.getOperatorName())
                    .createTime(r.getCreateTime() != null ? r.getCreateTime().format(TIME_FMT) : null)
                    .build());
        }
        return out;
    }

    /**
     * 读某版本的脚本原文（页面点脚本名查看）。文件名不取自客户端传入——只拿版本号去
     * 目录扫描结果里查，查不到就 404，天然免疫路径穿越；历史基线（1.0.0）没有实体文件，
     * 走到这里就是查不到，提示语说清这一点。
     */
    public SchemaScriptVO script(String version, boolean undo) {
        Path dir = requireDir();
        String name = scanFiles(dir, undo ? U_FILE : V_FILE).get(version);
        if (name == null) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "sql/versions 下没有 " + (undo ? "U" : "V") + version + "__*.sql"
                            + ("1.0.0".equals(version) ? "（历史基线是引导脚本登记的合集，没有实体文件）" : ""));
        }
        return SchemaScriptVO.builder()
                .version(version)
                .scriptName(name)
                .undo(undo)
                .content(readUtf8(dir.resolve(name)))
                .build();
    }

    /* ==================== 写：应用与一键回滚 ==================== */

    /**
     * 应用一个待应用/已回滚/应用失败的版本：逐条执行 V 脚本，全部成功才登记台账。
     * 半途失败时前几条已经落库（MySQL DDL 隐式提交、无法回卷），台账记 FAILED 并抛错，
     * 人工修补后重放——这是所有迁移工具共同的诚实边界，不是本实现的缺陷。
     */
    public SchemaVersionVO apply(String version, Long operatorId) {
        if (!changeLock.tryLock()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "已有变更正在执行，请稍后重试");
        }
        try {
            Path dir = requireDir();
            requireTable();
            SchemaMigration row = selectByVersion(version);
            if (row != null && row.getStatus() == SchemaMigration.STATUS_APPLIED) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "版本 " + version + " 已是已应用状态");
            }
            String vFile = scanFiles(dir, V_FILE).get(version);
            if (vFile == null) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "sql/versions 下没有 V" + version + "__*.sql");
            }
            SchemaOverviewVO snap = overview();
            snap.getItems().stream()
                    .filter(i -> Boolean.TRUE.equals(i.getCanApply()) && !i.getVersion().equals(version))
                    .findFirst()
                    .ifPresent(i -> {
                        throw new BusinessException(ResultCode.BUSINESS_ERROR,
                                "脚本有依赖，须按版本号升序应用：请先应用 " + i.getVersion());
                    });

            String uFile = scanFiles(dir, U_FILE).get(version);
            String content = readUtf8(dir.resolve(vFile));
            List<String> statements = splitStatements(content);
            String operatorName = lookupOperator(operatorId);
            long t0 = System.currentTimeMillis();
            int ok = 0;
            try {
                for (String stmt : statements) {
                    jdbc().execute(stmt);
                    ok++;
                }
            } catch (Exception e) {
                String reason = rootMessage(e);
                writeLog(version, SchemaMigrationLog.ACTION_APPLY, SchemaMigrationLog.RESULT_FAILED,
                        ok, System.currentTimeMillis() - t0, reason, operatorId, operatorName);
                upsertRow(row, version, vFile, uFile, content, SchemaMigration.STATUS_FAILED,
                        operatorId, operatorName, null);
                log.error("schema apply FAILED version={} stmt={}/{}: {}", version, ok + 1, statements.size(), reason);
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "第 " + (ok + 1) + "/" + statements.size() + " 条语句失败，前 " + ok
                                + " 条已落库（DDL 不可回卷），请人工核对后用同版本重放：" + reason);
            }
            upsertRow(row, version, vFile, uFile, content, SchemaMigration.STATUS_APPLIED,
                    operatorId, operatorName, LocalDateTime.now());
            writeLog(version, SchemaMigrationLog.ACTION_APPLY, SchemaMigrationLog.RESULT_SUCCESS,
                    ok, System.currentTimeMillis() - t0, null, operatorId, operatorName);
            log.info("schema apply SUCCESS version={} statements={} operator={}", version, ok, operatorName);
            return findItem(version);
        } finally {
            changeLock.unlock();
        }
    }

    /**
     * 一键回滚：执行该版本的 U 脚本。只允许回滚「版本号最高的已应用版本」（LIFO），
     * 跳版本回滚会留下结构依赖断层，服务端直接拒绝；U 脚本要求幂等，失败可重试。
     */
    public SchemaVersionVO rollback(String version, Long operatorId) {
        if (!changeLock.tryLock()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "已有变更正在执行，请稍后重试");
        }
        try {
            Path dir = requireDir();
            requireTable();
            SchemaMigration row = selectByVersion(version);
            if (row == null) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "台账里没有版本 " + version);
            }
            if (row.getStatus() != SchemaMigration.STATUS_APPLIED) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "该版本当前是「"
                        + statusText(row, null, false) + "」状态，不可回滚");
            }
            if (row.getUndoName() == null) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "该版本没有登记回滚脚本（历史基线不可一键回滚）");
            }
            if (!Files.exists(dir.resolve(row.getUndoName()))) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "回滚脚本文件缺失：" + row.getUndoName() + "（被删了？先补文件再回滚）");
            }
            String latest = null;
            for (SchemaMigration r : loadRows().values()) {
                if (r.getStatus() == SchemaMigration.STATUS_APPLIED
                        && (latest == null || compareVersion(r.getVersion(), latest) > 0)) {
                    latest = r.getVersion();
                }
            }
            if (latest != null && compareVersion(version, latest) != 0) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "只能回滚最近一次已应用的版本 " + latest + "（LIFO，跳版本会留结构断层）");
            }

            List<String> statements = splitStatements(readUtf8(dir.resolve(row.getUndoName())));
            String operatorName = lookupOperator(operatorId);
            long t0 = System.currentTimeMillis();
            int ok = 0;
            try {
                for (String stmt : statements) {
                    jdbc().execute(stmt);
                    ok++;
                }
            } catch (Exception e) {
                // 台账保持 APPLIED：U 脚本要求幂等，失败后人工重试回滚即可，不把模糊的中间态写死成失败
                String reason = rootMessage(e);
                writeLog(version, SchemaMigrationLog.ACTION_ROLLBACK, SchemaMigrationLog.RESULT_FAILED,
                        ok, System.currentTimeMillis() - t0, reason, operatorId, operatorName);
                log.error("schema rollback FAILED version={} stmt={}/{}: {}", version, ok + 1, statements.size(), reason);
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "回滚第 " + (ok + 1) + "/" + statements.size() + " 条语句失败（U 脚本须幂等，可直接重试）：" + reason);
            }
            row.setStatus(SchemaMigration.STATUS_ROLLED_BACK);
            row.setRolledBackAt(LocalDateTime.now());
            row.setOperatorId(operatorId);
            row.setOperatorName(operatorName);
            migrationMapper.updateById(row);
            writeLog(version, SchemaMigrationLog.ACTION_ROLLBACK, SchemaMigrationLog.RESULT_SUCCESS,
                    ok, System.currentTimeMillis() - t0, null, operatorId, operatorName);
            log.info("schema rollback SUCCESS version={} statements={} operator={}", version, ok, operatorName);
            return findItem(version);
        } finally {
            changeLock.unlock();
        }
    }

    /* ==================== 内部：目录 / 台账 / 语句解析 ==================== */

    private Path resolveDir(boolean strict) {
        String cfg = adminProperties.getSchemaDir();
        if (cfg != null && !cfg.isBlank()) {
            Path p = Paths.get(cfg.trim());
            if (Files.isDirectory(p)) {
                return p;
            }
            if (strict) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "im.admin.schema-dir 指向的目录不存在：" + p);
            }
            return null;
        }
        // 配置留空：从工作目录向上找 sql/versions（仓库根启动命中 ./，im-bootstrap 启动命中 ../）
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path cand = p.resolve("sql").resolve("versions");
            if (Files.isDirectory(cand)) {
                return cand;
            }
        }
        if (strict) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "找不到 sql/versions 目录，可在 application.yml 用 im.admin.schema-dir 指定绝对路径");
        }
        return null;
    }

    private Path requireDir() {
        return resolveDir(true);
    }

    private String describeProbe() {
        String cfg = adminProperties.getSchemaDir();
        return cfg != null && !cfg.isBlank() ? cfg.trim() : Paths.get("").toAbsolutePath() + " \u2192 向上 5 层内的 sql/versions";
    }

    private boolean tableExists() {
        try {
            Long n = jdbc().queryForObject("SELECT COUNT(*) FROM im_schema_migration", Long.class);
            return n != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void requireTable() {
        if (!tableExists()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, BOOTSTRAP_HINT);
        }
    }

    /** 目录里符合模式的脚本：版本号 -> 文件名（同号取字典序第一个，正常不该出现同号双份） */
    private Map<String, String> scanFiles(Path dir, Pattern pattern) {
        Map<String, String> out = new TreeMap<>(this::compareVersion);
        try (Stream<Path> files = Files.list(dir)) {
            files.map(f -> f.getFileName().toString()).sorted().forEach(name -> {
                Matcher m = pattern.matcher(name);
                if (m.matches()) {
                    out.putIfAbsent(m.group(1), name);
                }
            });
        } catch (IOException e) {
            log.warn("scan sql/versions failed: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, SchemaMigration> loadRows() {
        Map<String, SchemaMigration> out = new LinkedHashMap<>();
        for (SchemaMigration r : migrationMapper.selectList(null)) {
            out.put(r.getVersion(), r);
        }
        return out;
    }

    private SchemaMigration selectByVersion(String version) {
        return migrationMapper.selectOne(new LambdaQueryWrapper<SchemaMigration>()
                .eq(SchemaMigration::getVersion, version));
    }

    /**
     * 应用成功后写台账：无行插入、有行（已回滚/失败重放）原地更新同一 version 行——
     * uk_version 保证一个版本永远一行，页面才读得出「当前态」。
     */
    private void upsertRow(SchemaMigration existing, String version, String vFile, String uFile,
                           String content, int status, Long operatorId, String operatorName, LocalDateTime appliedAt) {
        SchemaMigration row = existing != null ? existing : new SchemaMigration();
        row.setVersion(version);
        row.setScriptName(vFile);
        row.setUndoName(uFile);
        row.setDescription(firstCommentMeaning(content));
        row.setChecksum(sha256Hex(requireDir().resolve(vFile)));
        row.setStatus(status);
        row.setOperatorId(operatorId);
        row.setOperatorName(operatorName);
        if (appliedAt != null) {
            row.setAppliedAt(appliedAt);
            row.setRolledBackAt(null);
        }
        if (existing == null) {
            migrationMapper.insert(row);
        } else {
            migrationMapper.updateById(row);
        }
    }

    private void writeLog(String version, String action, String result, int statements,
                          long durationMs, String message, Long operatorId, String operatorName) {
        SchemaMigrationLog entry = new SchemaMigrationLog();
        entry.setVersion(version);
        entry.setAction(action);
        entry.setResult(result);
        entry.setStatementCount(statements);
        entry.setDurationMs(durationMs);
        entry.setMessage(message != null && message.length() > 1000 ? message.substring(0, 1000) : message);
        entry.setOperatorId(operatorId);
        entry.setOperatorName(operatorName);
        logMapper.insert(entry);
    }

    private SchemaVersionVO findItem(String version) {
        return overview().getItems().stream()
                .filter(i -> i.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "版本 " + version + " 不在清单里"));
    }

    private String statusText(SchemaMigration row, String vFile, boolean checksumMatched) {
        if (row == null) {
            return vFile != null ? "待应用" : "未知";
        }
        boolean baseline = row.getChecksum() == null;
        return switch (row.getStatus() == null ? -1 : row.getStatus()) {
            case SchemaMigration.STATUS_APPLIED -> baseline ? "历史基线"
                    : (vFile != null && !checksumMatched ? "已应用（脚本已被改动!）" : "已应用");
            case SchemaMigration.STATUS_ROLLED_BACK -> "已回滚";
            case SchemaMigration.STATUS_FAILED -> "应用失败";
            default -> "未知";
        };
    }

    private String lookupOperator(Long operatorId) {
        try {
            String name = jdbc().queryForObject(
                    "SELECT IFNULL(nickname, username) FROM im_user WHERE id = ?", String.class, operatorId);
            return name != null ? name : "user:" + operatorId;
        } catch (Exception e) {
            return "user:" + operatorId;
        }
    }

    /**
     * 逐字符状态机切语句：跳过 {@code --}/{@code #} 行注释与块注释，字符串/反引号里的分号不算分隔符。
     * 明确拒绝 DELIMITER（存储过程写法），出现即报错——版本脚本只收可直接 execute 的语句。
     */
    static List<String> splitStatements(String raw) {
        if (raw.toUpperCase().contains("DELIMITER")) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "版本脚本不支持 DELIMITER/存储过程，请改成幂等 DDL");
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char[] cs = raw.toCharArray();
        boolean inSq = false, inDq = false, inBq = false, inLine = false, inBlock = false;
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (inLine) {
                if (inBlock && c == '*' && i + 1 < cs.length && cs[i + 1] == '/') {
                    inBlock = false;
                    i++;
                } else if (!inBlock && c == '\n') {
                    inLine = false;
                    cur.append('\n');
                } else if (!inBlock && c == '/' && i + 1 < cs.length && cs[i + 1] == '*') {
                    // 行注释里出现的 /* 不启块注释，等本行结束
                    i++;
                }
                continue;
            }
            if (inSq) {
                cur.append(c);
                if (c == '\\' && i + 1 < cs.length) {
                    cur.append(cs[++i]);
                } else if (c == '\'') {
                    if (i + 1 < cs.length && cs[i + 1] == '\'') {
                        cur.append(cs[++i]);
                    } else {
                        inSq = false;
                    }
                }
                continue;
            }
            if (inDq) {
                cur.append(c);
                if (c == '\\' && i + 1 < cs.length) {
                    cur.append(cs[++i]);
                } else if (c == '"') {
                    inDq = false;
                }
                continue;
            }
            if (inBq) {
                cur.append(c);
                if (c == '`') {
                    inBq = false;
                }
                continue;
            }
            if (c == '-' && i + 1 < cs.length && cs[i + 1] == '-') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '#') {
                inLine = true;
                continue;
            }
            if (c == '/' && i + 1 < cs.length && cs[i + 1] == '*') {
                inLine = true;
                inBlock = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSq = true;
            } else if (c == '"') {
                inDq = true;
            } else if (c == '`') {
                inBq = true;
            } else if (c == ';') {
                String stmt = cur.toString().trim();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        if (out.isEmpty()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "脚本里解析不出任何语句");
        }
        return out;
    }

    /** 版本号按 . 和 - 分段比较：数字段按数值、其余按字典序，段少者为前缀（1.0.4 &lt; 1.0.10） */
    int compareVersion(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            if (i >= pa.length) {
                return -1;
            }
            if (i >= pb.length) {
                return 1;
            }
            String sa = pa[i], sb = pb[i];
            if (sa.matches("\\d+") && sb.matches("\\d+")) {
                int cmp = Long.compare(Long.parseLong(sa), Long.parseLong(sb));
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                int cmp = sa.compareTo(sb);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    /** 台账 description 取脚本头注释里第一行有含义的文字（跳过 ==== 分隔线），截到列宽 255 */
    private String firstCommentMeaning(String content) {
        for (String line : content.split("\n", 20)) {
            String t = line.trim();
            if (!t.startsWith("--")) {
                if (!t.isEmpty()) {
                    break;
                }
                continue;
            }
            t = t.replaceFirst("^--+\\s*", "").trim();
            if (!t.isEmpty() && !t.chars().allMatch(ch -> ch == '=' || ch == '-')) {
                return t.length() > 255 ? t.substring(0, 255) : t;
            }
        }
        return null;
    }

    private String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "脚本读取失败：" + file + " " + e.getMessage());
        }
    }

    private String sha256Hex(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "checksum 计算失败：" + e.getMessage());
        }
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
