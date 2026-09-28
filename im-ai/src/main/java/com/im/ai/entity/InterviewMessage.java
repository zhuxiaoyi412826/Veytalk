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
 * AI 面试问答记录：逐轮留存的提问与作答。
 *
 * <p>单独成表而不是往会话表塞一个 JSON 大字段：审计要能回答「他是在答第几题时切屏的」，
 * 整段 JSON 既撑爆行又只能全量读回。
 *
 * <p>{@code seq} 才是排序依据，不用 create_time：一轮问答的两行往往落在同一毫秒，
 * 排出来谁先谁后是随机的，而面试记录读反了就没有意义。
 *
 * <p>幂等靠 {@code uk_session_turn}（session_id + turn_no + role）：seq 是服务端现算的，
 * 重传一次就会算出一个新值，拿它当幂等键等于没加；turn_no 是前端报的「这是第几题」，
 * 重试才能撞回同一把键。作答行的 turn_no 取题号减一（它答的是上一题）。
 *
 * <p>继承 {@link AuditEntity}（无 deleted）：流水只增不删。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_interview_message")
public class InterviewMessage extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色：面试官提问 */
    public static final String ROLE_ASSISTANT = "assistant";
    /** 角色：候选人作答 */
    public static final String ROLE_USER = "user";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属面试会话 ID */
    private Long sessionId;

    /** 会话内序号，从 1 递增 */
    private Integer seq;

    /** 属于第几轮：提问行是本题题号，作答行是题号减一 */
    private Integer turnNo;

    /** 角色：assistant / user */
    private String role;

    /** 正文，写入前按 im.ai.max-message-chars 截断 */
    private String content;

    /** 正文字符数：审计时看作答长度分布，不必把正文读回来 */
    private Integer charCount;

    /** 候选人从收到本题到提交的耗时（毫秒）；role=assistant 固定为 0 */
    private Long elapsedMs;
}
