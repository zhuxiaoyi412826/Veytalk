package com.im.file.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import com.im.file.dto.req.ModerationReportReq;
import com.im.file.dto.vo.ModerationPolicyVO;
import com.im.file.enums.FileBizType;
import com.im.file.service.ModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 审核策略下发与命中留痕。
 *
 * <p>审计走独立 logger {@code com.im.moderation}（见 im-common 的 logback-spring.xml），
 * 而不是用本类的 logger：追责记录要单独落一份文件、且不能跟着 info/warn 一起被海量常规日志冲走。
 *
 * <p>这里对上报内容一律「不采信、只留痕，但必须洗干净再写」：文件名与类别名都是客户端说了算的
 * 自由文本，直接拼进日志等于给对方一个伪造日志行（换行、伪造另一条记录）的机会，
 * 所以先折行、去引号再截断。概率强制夹在 0~1，写出来的数字格式永远是两位小数，不会出现
 * {@code prob=1e309} 这种把正则抽取搞崩的东西。
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

    private final ImProperties imProperties;

    @Override
    public ModerationPolicyVO policy() {
        ImProperties.Moderation config = imProperties.getModeration();
        ModerationPolicyVO vo = new ModerationPolicyVO();
        vo.setEnabled(config.isEnabled());
        vo.setAuditEnabled(config.isAuditEnabled());
        vo.setThresholds(cleanThresholds(config));
        return vo;
    }

    @Override
    public void report(Long userId, ModerationReportReq req) {
        ImProperties.Moderation config = imProperties.getModeration();
        // 总开关关掉时前端本不该来，走到这里就只留一条 DEBUG，不占审计文件的行
        if (!config.isEnabled()) {
            log.debug("[审核] 总开关关闭，忽略上报: userId={}, scene={}", userId, req.getScene());
            return;
        }
        // 场景必须是图片类业务：审计记录里混进 chat_file 之类的值，说明上报方是乱写的
        FileBizType scene = requireImageScene(req.getScene());
        if (!config.isAuditEnabled()) {
            // 只拦不记模式：仍然把非法场景挡掉，保证接口语义不随配置漂移
            log.debug("[审核] 审计已关闭，命中仅拦截不记录: userId={}, scene={}", userId, scene.getCode());
            return;
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
}
