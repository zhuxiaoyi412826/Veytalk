package com.im.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 一次付费模型调用的用量流水（AI 护栏 · 四期第六批）。
 *
 * <p>只增不改不删：它是「这个人烧了多少钱」的账本，账本没有编辑键。
 * 继承 {@link AuditEntity} 而不是 {@code BaseEntity}：流水表物理保留、无 {@code deleted} 列，
 * 与 {@code im_interview_event} 同一取向。
 *
 * <p>{@code durationMs} 是计费口径（模型调用实际耗时，服务端自己量，见
 * {@code ImProperties.AiGuard}）；{@code success=0} 的行只留痕不扣预算。
 * 场景名不做枚举外键——厂商能力会增减，字符串让「加一个场景」不牵动这张表。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_ai_usage")
public class AiUsage extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 发起人用户 ID */
    private Long userId;

    /** 场景：interview_chat / interview_asr / interview_tts / message_to_text */
    private String scene;

    /** 模型调用耗时（毫秒） */
    private Long durationMs;

    /** 是否成功：1 成功（扣预算） 0 失败（只留痕） */
    private Integer success;

    /** 失败原因摘要（成功为 null），落库前截到 255 */
    private String errorMsg;
}
