package com.im.admin.service;

import com.im.admin.config.AdminProperties;
import com.im.admin.dto.vo.LogContentVO;
import com.im.admin.dto.vo.LogFileVO;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志管理：把 logback 落在磁盘上的分档日志安全地读回后台页面（三期）。
 *
 * <h2>路径穿越是这里唯一的重安全问题</h2>
 *
 * <p>防线有两层，第一层就够了，第二层是保险：
 * <ol>
 *   <li><b>前端根本不传路径</b>：只传白名单 key（{@code error} / {@code slowSql}…），
 *       key→相对路径的映射是本类里的不可变常量表。传任何不在表里的值直接 400，
 *       {@code ../} 编码得再花哨也进不了文件系统调用；</li>
 *   <li>解析后仍做 {@code normalized.startsWith(root)} 断言：防的是「以后有人往映射表里
 *       加了条脏相对路径」——表本身也可能被改坏，最后一道校验不该省。</li>
 * </ol>
 *
 * <h2>只尾读，不下载，不全文件搜</h2>
 *
 * <p>单档最大 50MB（滚动上限），整读会把后台自己拖死；下载端点等于开一条把
 * （可能含手机号、审核命中记录的）日志外带出境的路。尾读窗口默认 200 行、
 * 上限走 {@code im.admin.log-tail-max-lines}；关键词过滤发生在尾读窗口内，
 * 语义是「在最近 N 行里找」，这一点在响应的 filterNote 里明说，不让管理员以为搜了全文件。
 *
 * <p>归档文件（带日期的那些）不在本页：一期先管「此刻正在写的六个文件」，
 * 历史回溯本来就该 grep/Select-String 在服务器上做，不该由 web 端代理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLogService {

    /** 单次尾读的字节窗口起点（不够再 4 倍扩，最大到整档 8MB——超过就让磁盘上的文件自己说话） */
    private static final int INITIAL_WINDOW_BYTES = 64 * 1024;
    private static final int MAX_WINDOW_BYTES = 8 * 1024 * 1024;
    private static final int DEFAULT_TAIL = 200;

    /** 日志档位定义：key → (中文名, 相对路径, 是否敏感)。与 logback-spring.xml 的落地布局一一对应 */
    private record Definition(String label, String relPath, boolean sensitive) {
    }

    private static final Map<String, Definition> CATALOG = buildCatalog();

    private static Map<String, Definition> buildCatalog() {
        Map<String, Definition> m = new LinkedHashMap<>();
        m.put("debug", new Definition("控制台镜像（本次运行）", "DEBUG.log", false));
        m.put("info", new Definition("INFO", "info.log", false));
        m.put("warn", new Definition("WARN", "warn.log", false));
        m.put("error", new Definition("ERROR", "error.log", true));
        m.put("slowSql", new Definition("慢 SQL", "SQL/slow-sql.log", true));
        m.put("slowApi", new Definition("慢接口", "SLOW-API/slow-api.log", true));
        m.put("moderation", new Definition("图片审核命中", "MODERATION/moderation.log", true));
        return Map.copyOf(m);
    }

    private final ImProperties imProperties;
    private final AdminProperties adminProperties;

    /** 列出全部日志档（含不存在的：还没写过 error 的实例上 error.log 缺席是常态不是故障） */
    public List<LogFileVO> files() {
        Path root = logRoot();
        List<LogFileVO> vos = new ArrayList<>(CATALOG.size());
        for (Map.Entry<String, Definition> entry : CATALOG.entrySet()) {
            Definition def = entry.getValue();
            Path file = safeResolve(root, def.relPath());
            boolean exists = Files.isRegularFile(file);
            vos.add(LogFileVO.builder()
                    .key(entry.getKey())
                    .label(def.label())
                    .relPath(def.relPath())
                    .exists(exists)
                    .sizeBytes(exists ? sizeOf(file) : null)
                    .lastModified(exists ? lastModified(file) : null)
                    .sensitive(def.sensitive())
                    .build());
        }
        return vos;
    }

    /**
     * 尾读一个日志档。
     *
     * @param key     白名单逻辑标识
     * @param tail    期望行数，null 取 200；服务端夹到 {@code [1, im.admin.log-tail-max-lines]}
     * @param keyword 可选关键词，在最近 N 行里做不区分大小写的包含匹配
     */
    public LogContentVO read(String key, Integer tail, String keyword) {
        Definition def = CATALOG.get(key == null ? "" : key);
        if (def == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知的日志档：" + key);
        }
        int maxLines = (int) Math.max(1, adminProperties.getLogTailMaxLines());
        int want = tail == null ? DEFAULT_TAIL : Math.min(Math.max(1, tail), maxLines);

        Path root = logRoot();
        Path file = safeResolve(root, def.relPath());
        if (!Files.isRegularFile(file)) {
            return LogContentVO.builder().key(key).relPath(def.relPath()).exists(false)
                    .lines(List.of()).returnedLines(0).truncated(false)
                    .filterNote("该文件尚未生成（这一档还没有过输出）").build();
        }
        try {
            TailResult tailResult = readTail(file, want);
            List<String> lines = tailResult.lines();
            String note;
            if (TextUtil.isNotBlank(keyword)) {
                String kw = keyword.toLowerCase(java.util.Locale.ROOT);
                lines = lines.stream().filter(l -> l.toLowerCase(java.util.Locale.ROOT).contains(kw)).toList();
                note = "关键词过滤发生在最近 " + tailResult.lines().size() + " 行内，不是全文件搜索";
            } else {
                note = null;
            }
            return LogContentVO.builder()
                    .key(key)
                    .relPath(def.relPath())
                    .exists(true)
                    .lines(lines)
                    .returnedLines(lines.size())
                    .truncated(tailResult.windowTruncated() || lines.size() >= want)
                    .filterNote(note)
                    .build();
        } catch (IOException e) {
            log.warn("[日志管理] 读取失败 key={}: {}", key, e.getMessage());
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "日志读取失败：" + e.getMessage());
        }
    }

    /* ==================== 内部 ==================== */

    /** 尾读结果：行集 + 窗口是否没覆盖到文件头（还有更早内容没读到） */
    private record TailResult(List<String> lines, boolean windowTruncated) {
    }

    private TailResult readTail(Path file, int want) throws IOException {
        long size = Files.size(file);
        long window = Math.min(size, INITIAL_WINDOW_BYTES);
        List<String> lines;
        boolean truncated;
        while (true) {
            long start = Math.max(0, size - window);
            lines = readLinesFrom(file, start);
            truncated = start > 0;
            // 行数够 或 已到文件头 或 窗口到上限，三种情况任一成立即收工
            if (lines.size() >= want || start == 0 || window >= MAX_WINDOW_BYTES) {
                break;
            }
            window = Math.min(size, window * 4);
        }
        if (lines.size() > want) {
            lines = new ArrayList<>(lines.subList(lines.size() - want, lines.size()));
        }
        return new TailResult(lines, truncated);
    }

    /** 从字节偏移处按行读到结尾；offset&gt;0 时丢掉第一行（多半是半行） */
    private List<String> readLinesFrom(Path file, long offset) throws IOException {
        byte[] buf;
        try (var ch = Files.newByteChannel(file)) {
            if (offset > 0) {
                ch.position(offset);
            }
            int len = (int) (Files.size(file) - offset);
            buf = new byte[len];
            int read = 0;
            while (read < len) {
                int n = ch.read(java.nio.ByteBuffer.wrap(buf, read, len - read));
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        // 窗口永远截到文件尾，结尾必是完整字符；而开头若腰斩了多字节字符，
        // 解出 U+FFFD 也无所谓——offset>0 时第一行本来就要丢掉（见下）
        String text = new String(buf, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.stream(text.split("\n", -1)).toList());
        if (offset > 0 && !lines.isEmpty()) {
            lines.remove(0);
        }
        // 去掉尾部空行（文件以 \n 结尾时 split 会多出一个空尾巴）
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        lines.replaceAll(s -> s.endsWith("\r") ? s.substring(0, s.length() - 1) : s);
        return lines;
    }

    private Path logRoot() {
        String home = imProperties.getLog().getHome();
        if (TextUtil.isBlank(home)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "日志根目录 im.log.home 未配置");
        }
        return Paths.get(home).toAbsolutePath().normalize();
    }

    /** 第二层保险：映射表出来的相对路径也要断言没逃出根目录 */
    private Path safeResolve(Path root, String relPath) {
        Path resolved = root.resolve(relPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "日志路径越界，已拒绝");
        }
        return resolved;
    }

    private Long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    private LocalDateTime lastModified(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return null;
        }
    }
}
