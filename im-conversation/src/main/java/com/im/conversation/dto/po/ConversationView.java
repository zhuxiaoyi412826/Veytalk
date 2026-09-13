package com.im.conversation.dto.po;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话列表查询投影：一次 JOIN 同时取出会话摘要与「我」的成员视角字段。
 *
 * <p>之所以不复用 {@code Conversation} 实体：列表排序依赖
 * {@code m.is_top DESC, m.top_time DESC, c.last_msg_time DESC}，横跨两张表，
 * 只有 JOIN 才能让数据库完成排序与分页；实体无法承载成员维度字段。
 *
 * <p>列名到属性名的映射依赖 {@code map-underscore-to-camel-case}，
 * 其中 {@code is_top} / {@code is_muted} 通过 SQL 别名对齐属性名 {@code top} / {@code muted}。
 */
@Data
public class ConversationView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /* ---------- 来自 im_conversation ---------- */

    private Long id;

    /** 类型：1 单聊 2 群聊 */
    private Integer type;

    /** 群聊为群 ID；单聊为 NULL */
    private Long targetId;

    private Long lastMsgId;

    private String lastMsgContent;

    private Integer lastMsgType;

    private LocalDateTime lastMsgTime;

    /* ---------- 来自 im_conversation_member ---------- */

    private Integer unreadCount;

    private Long lastAckSeq;

    /** 是否置顶：1 是 0 否 */
    private Integer top;

    private LocalDateTime topTime;

    /** 是否免打扰：1 是 0 否 */
    private Integer muted;

    /** 是否有未读 @ 提醒：1 有 0 无 */
    private Integer atFlag;
}
