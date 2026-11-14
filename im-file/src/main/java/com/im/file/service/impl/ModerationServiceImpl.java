package com.im.file.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.ModerationScoreDTO;
import com.im.common.domain.UploadCmd;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import com.im.common.util.TextUtil;
import com.im.file.dto.req.ModerationReportReq;
import com.im.file.dto.vo.ModerationPolicyVO;
import com.im.file.entity.FileEntity;
import com.im.file.entity.ModerationLog;
import com.im.file.enums.FileBizType;
import com.im.file.mapper.ModerationLogMapper;
import com.im.file.service.FileService;
import com.im.file.service.ModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 审核策略下发、命中留痕与隔离区取证。
 *
 * <p>审计走独立 logger {@code com.im.moderation}（见 im-common 的 logback-spring.xml），
 * 而不是用本类的 logger：追责记录要单独落一份文件、且不能跟着 info/warn 一起被海量常规日志冲走。
 *
 * <p>这里对上报内容一律「不采信、只留痕，但必须洗干净再写」：文件名与类别名都是客户端说了算的
 * 自由文本，直接拼进日志等于给对方一个伪造日志行（换行、伪造另一条记录）的机会，
 * 所以先折行、去引号再截断。概率强制夹在 0~1，写出来的数字格式永远是两位小数，不会出现
 * {@code prob=1e309} 这种把正则抽取搞崩的东西。
 *
 * <p>{@link #attachEvidence} 是本类唯一会往存储写字节的方法，所以它的约束全部是
 * 「拒绝」而不是「降级」：越权、超限直接抛错回给发送端（发送端对它仍是静默忽略）。
 * 副本走 {@code FileService#store}，与任何一次普通上传共用同一套类型与大小校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements ModerationService {

    /** 审计通道专用 logger：只写 MODERATION/moderation.log，不进 info/warn/error 档 */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.im.moderation");

    /** 连续空白（含换行、制表）折成一个空格，日志条目必须一行一条 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 概率输出格式：定长两位小数，事后按数值排序与正则抽取都省事 */
    private static final String PROBABILITY_FORMAT = "%.3f";

    /** 隔离区上限没配或配成 0 时的兜底：与 ImProperties 的默认值一致，不让「没配」变成「不限」 */
    private static final DataSize DEFAULT_QUARANTINE_MAX_SIZE = DataSize.ofMegabytes(10);

    private final ImProperties imProperties;
    private final ModerationLogMapper moderationLogMapper;
    private final FileService fileService;
    private final JsonUtil jsonUtil;

    @Override
    public ModerationPolicyVO policy() {
        ImProperties.Moderation config = imProperties.getModeration();
        ModerationPolicyVO vo = new ModerationPolicyVO();
        vo.setEnabled(config.isEnabled());
        vo.setAuditEnabled(config.isAuditEnabled());
        vo.setThresholds(cleanThresholds(config));
        vo.setQuarantineEnabled(config.isQuarantineEnabled());
        vo.setQuarantineMaxSize(quarantineMaxSize(config).toBytes());
        return vo;
    }

    @Override
    public Long report(Long userId, ModerationReportReq req) {
        ImProperties.Moderation config = imProperties.getModeration();
        // 总开关关掉时前端本不该来，走到这里就只留一条 DEBUG，不占审计文件的行
        if (!config.isEnabled()) {
            log.debug("[审核] 总开关关闭，忽略上报: userId={}, scene={}", userId, req.getScene());
            return null;
        }
        // 场景必须是图片类业务：审计记录里混进 chat_file 之类的值，说明上报方是乱写的
        FileBizType scene = requireImageScene(req.getScene());
        if (!config.isAuditEnabled()) {
            // 只拦不记模式：仍然把非法场景挡掉，保证接口语义不随配置漂移
            log.debug("[审核] 审计已关闭，命中仅拦截不记录: userId={}, scene={}", userId, scene.getCode());
            return null;
        }
        int maxText = Math.max(8, config.getMaxTextLength());
        int maxEntries = Math.max(1, config.getMaxScoreEntries());

        StringBuilder line = new StringBuilder("[审核命中]")
                .append(" userId=").append(userId)
                .append(" scene=").append(scene.getCode())
                .append(" class=").append(oneLine(req.getHitClass(), 32))
                .append(" prob=").append(probability(req.getHitProbability()))
                .append(" threshold=").append(probability(req.getThreshold()))
                .append(" size=").append(req.getFileSize() == null ? "-" : req.getFileSize());
        if (TextUtil.isNotBlank(req.getFileName())) {
            line.append(" name=\"").append(oneLine(req.getFileName(), maxText)).append('"');
        }
        if (req.getConversationId() != null) {
            line.append(" conv=").append(req.getConversationId());
        }
        String scores = formatScores(req.getScores(), maxEntries);
        if (!scores.isEmpty()) {
            line.append(" scores=").append(scores);
        }
        AUDIT.info(line.toString());
        // 双写（五期·路线 2）：审计文件那条链路先走完、不受库影响，然后再落表。
        // 表写失败只留一条 WARN：追责现场已经在文件里了，不能因为数据库抖一下就把
        // 接口报错给发送端——拦图这件事在客户端已经生效，上报只是尽力而为的留痕。
        // 返回的流水 ID 是「能不能挂证据」的唯一依据：拿不到就回 null，发送端不再传副本，
        // 存储里因此不会多出没有记录可挂的孤儿字节。
        return persist(userId, scene, req, maxText, maxEntries);
    }

    @Override
    public void attachEvidence(Long logId, UploadCmd cmd) {
        BusinessException.throwIf(logId == null, ResultCode.BAD_REQUEST, "缺少审核记录 ID");
        BusinessException.throwIf(cmd == null || cmd.getUploaderId() == null, ResultCode.UNAUTHORIZED);
        ImProperties.Moderation config = imProperties.getModeration();
        if (!config.isQuarantineEnabled()) {
            // 开关关掉后前端本不该来；仍然来了就按「这条记录不收图」处理，不落字节也不报错，
            // 免得一个已经生效的开关把发送端的一次无害投递变成一次失败
            log.debug("[审核] 隔离区已关闭，丢弃证据副本: logId={}, uploader={}", logId, cmd.getUploaderId());
            return;
        }
        ModerationLog row = moderationLogMapper.selectById(logId);
        BusinessException.throwIf(row == null, ResultCode.NOT_FOUND, "审核记录不存在");
        // 只能挂到自己那条记录上：logId 是自增/雪花可见的，不校验就成了「往别人的记录里塞图」
        BusinessException.throwUnless(cmd.getUploaderId().equals(row.getUserId()),
                ResultCode.FORBIDDEN, "只能给自己的审核记录挂证据");
        if (row.getEvidenceFileId() != null) {
            // 幂等：重复投递（重试、双开页面）不再传第二份字节，也不覆盖第一份
            log.debug("[审核] 记录已挂过证据，跳过重复投递: logId={}, fileId={}", logId, row.getEvidenceFileId());
            return;
        }
        long maxSize = quarantineMaxSize(config).toBytes();
        long size = cmd.getBytes() == null ? 0L : cmd.getBytes().length;
        BusinessException.throwIf(size <= 0, ResultCode.FILE_EMPTY);
        // UPLOAD_TOO_LARGE 的文案没有占位符，带细节只会悄悄丢掉，所以数字留在日志里
        if (size > maxSize) {
            log.warn("[审核] 证据副本超过隔离区上限，已拒收: logId={}, size={}, max={}", logId, size, maxSize);
            throw new BusinessException(ResultCode.UPLOAD_TOO_LARGE);
        }

        // 业务类型由服务端点名，不采信调用方传的值：同一个上传入口如果被允许写 chat_image，
        // 「上传一张违规图」和「给记录挂证据」就成了同一件事，隔离区会退化成一个普通上传的别名
        cmd.setBizType(FileBizType.MODERATION_QUARANTINE.getCode());
        cmd.setDuration(null);
        FileEntity stored = fileService.store(cmd);

        ModerationLog patch = new ModerationLog();
        patch.setId(logId);
        patch.setEvidenceFileId(stored.getId());
        try {
            moderationLogMapper.updateById(patch);
        } catch (Exception e) {
            // 字节已经落进存储、记录却挂不上：这是一条没人引用的孤儿记录，把 fileId 说清楚才可能事后回收
            log.error("[审核] 证据挂载失败（字节已入库且无人引用）: logId={}, fileId={}, err={}",
                    logId, stored.getId(), e.getMessage());
            throw e;
        }
        log.info("[审核] 隔离区证据已挂载: logId={}, fileId={}, size={}, uploader={}",
                logId, stored.getId(), size, cmd.getUploaderId());
    }

    /**
     * 隔离区上限：配置缺失或配成非正数时退回默认值。
     *
     * <p>这一条比 {@code im.file.max-size} 小是正常的：证据只需要「看得出判得对不对」，
     * 不需要承载原始大图，上限压低是在给存储划一道独立的、可预期的增量。
     */
    private DataSize quarantineMaxSize(ImProperties.Moderation config) {
        DataSize configured = config.getQuarantineMaxSize();
        return configured == null || configured.toBytes() <= 0 ? DEFAULT_QUARANTINE_MAX_SIZE : configured;
    }

    /**
     * 命中流水落库（{@code im_moderation_log}），供后台「审核记录」屏按维度检索。
     *
     * <p>写的是清洗后的同一批值（与审计文件行同源）：概率夹到 0~1、字符串折行限长，
     * 不拿客户端原值直接进 SQL。整个方法吃异常，失败不影响接口返回。
     *
     * @return 落库成功的流水 ID；写失败返回 {@code null}（发送端据此不传副本）
     */
    private Long persist(Long userId, FileBizType scene, ModerationReportReq req, int maxText, int maxEntries) {
        try {
            ModerationLog row = new ModerationLog();
            row.setUserId(userId);
            row.setScene(scene.getCode());
            row.setHitClass(oneLine(req.getHitClass(), 32));
            row.setHitProbability(clamped(req.getHitProbability()));
            row.setThreshold(clamped(req.getThreshold()));
            row.setFileName(TextUtil.isBlank(req.getFileName()) ? null : oneLine(req.getFileName(), maxText));
            row.setFileSize(req.getFileSize());
            row.setConversationId(req.getConversationId());
            row.setScores(cleanScores(req.getScores(), maxEntries));
            moderationLogMapper.insert(row);
            return row.getId();
        } catch (Exception e) {
            log.warn("[审核] 命中流水落库失败（审计文件已写入，不重试）: userId={}, scene={}, err={}",
                    userId, scene.getCode(), e.getMessage());
            return null;
        }
    }

    /**
     * 评分列表 → 可入库的 JSON 串：与 {@link #formatScores} 同一套清洗与上限，
     * 只是输出从日志文本换成结构化 JSON，两侧条目数一致才能事后互相对账。
     */
    private String cleanScores(List<ModerationReportReq.Score> scores, int maxEntries) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        List<ModerationScoreDTO> cleaned = new ArrayList<>();
        for (ModerationReportReq.Score score : scores) {
            if (score == null || cleaned.size() >= maxEntries) {
                break;
            }
            String className = oneLine(score.getClassName(), 32);
            if ("-".equals(className)) {
                continue;
            }
            cleaned.add(new ModerationScoreDTO(className, clamped(score.getProbability())));
        }
        return cleaned.isEmpty() ? null : jsonUtil.toJson(cleaned);
    }

    /**
     * 阈值原样下发前先清一遍：null 值与越界概率都会让前端拿到一个没法用的判据，
     * 而配置写错是人事后才发现的事，宁可在下发时就把它收敛成「这条不生效」。
     */
    private Map<String, Double> cleanThresholds(ImProperties.Moderation config) {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        Map<String, Double> raw = config.getThresholds();
        if (raw == null || raw.isEmpty()) {
            return thresholds;
        }
        raw.forEach((className, value) -> {
            if (className == null || className.isBlank() || value == null || value <= 0 || value > 1) {
                log.warn("[审核] 阈值配置项无效，已跳过: class={}, threshold={}", className, value);
                return;
            }
            thresholds.put(className.trim(), value);
        });
        return thresholds;
    }

    /**
     * 场景校验：只接受图片类业务类型。
     *
     * <p>直播封面（video_cover）不在 {@link FileBizType#isImage()} 里，但它是公开面向所有用户
     * 渲染的图片，恰恰是最该拦的位置之一，所以单独算进来。
     */
    private FileBizType requireImageScene(String code) {
        FileBizType type = FileBizType.of(code);
        boolean imageScene = type != null && (type.isImage() || type == FileBizType.VIDEO_COVER);
        BusinessException.throwIf(!imageScene, ResultCode.BAD_REQUEST, "业务场景不是图片类型");
        return type;
    }

    private String formatScores(List<ModerationReportReq.Score> scores, int maxEntries) {
        if (scores == null || scores.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("[");
        int index = 0;
        for (ModerationReportReq.Score score : scores) {
            if (score == null || index >= maxEntries) {
                break;
            }
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(oneLine(score.getClassName(), 32)).append(':').append(probability(score.getProbability()));
            index++;
        }
        return builder.append(']').toString();
    }

    /**
     * 客户端自由文本 → 可安全拼进日志的单行串：折空白、去双引号与反斜杠、限长。
     */
    private String oneLine(String value, int maxLength) {
        if (TextUtil.isBlank(value)) {
            return "-";
        }
        String cleaned = WHITESPACE.matcher(value.trim()).replaceAll(" ").replace("\\", "").replace("\"", "");
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    /**
     * 概率收敛：null 与越界都记成 {@code -} / 夹到 0~1，绝不把客户端给的任意数字原样写出去。
     */
    private String probability(Double value) {
        if (value == null) {
            return "-";
        }
        double clamped = Math.min(1.0d, Math.max(0.0d, value));
        return String.format(Locale.ROOT, PROBABILITY_FORMAT, clamped);
    }

    /**
     * 概率收敛的数值版（供落库）：与 {@link #probability} 同一条夹逼规则，
     * 只是输出留在 double 上，库里 DECIMAL(5,4) 拿到的永远是 0~1。
     */
    private Double clamped(Double value) {
        if (value == null) {
            return null;
        }
        return Math.min(1.0d, Math.max(0.0d, value));
    }
}
