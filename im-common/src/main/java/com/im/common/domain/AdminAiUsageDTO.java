package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 用量护栏（四期第六批）的一次模型调用明细，数据在 im-ai 的 {@code im_ai_usage}。
 *
 * <p>{@code durationMs} 是护栏的计费口径（模型调用实际耗时，见 {@code ImProperties.AiGuard}
 * 的类注释），{@code success=0} 的行只留痕不扣预算。{@code userName} 由实现方经
 * {@code UserQuerySpi} 装配，注销用户显示为「已注销用户」——后台读的是历史台账，
 * 人不在了账也得在。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAiUsageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调用记录 ID */
    private Long usageId;

    /** 发起人用户 ID */
    private Long userId;

    /** 展示名（昵称优先，兜底账号名） */
    private String userName;

    /** 场景：interview_chat / interview_asr / interview_tts / message_to_text */
    private String scene;

    /** 模型调用耗时（毫秒） */
    private Long durationMs;

    /** 是否成功：1 成功（扣预算） 0 失败（只留痕） */
    private Integer success;

    /** 失败原因摘要（成功为 null） */
    private String errorMsg;

    /** 调用时间 */
    private LocalDateTime createTime;
}
