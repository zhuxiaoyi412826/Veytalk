package com.im.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import com.im.common.enums.ConvType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 会话主体。
 *
 * <p>会话本身与用户无关，用户维度的状态（未读数、置顶、免打扰、本端隐藏）全部落在
 * {@link ConversationMember} 上，这样同一个单聊会话对双方可以有不同的置顶与未读表现。
 *
 * <p>{@code bizKey} 上的唯一键 {@code uk_biz_key} 是 {@code getOrCreate} 幂等的基础：
 * 单聊为 {@code s:{小ID}:{大ID}}，群聊为 {@code g:{groupId}}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_conversation")
public class Conversation extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 类型：1 单聊 2 群聊 */
    private Integer type;

    /** 群聊为群 ID；单聊为 NULL，对方通过 {@code im_conversation_member} 解析 */
    private Long targetId;

    /** 业务唯一键 */
    private String bizKey;

    /** 最后一条消息 ID */
    private Long lastMsgId;

    /** 最后一条消息摘要，供会话列表直接展示 */
    private String lastMsgContent;

    /** 最后一条消息类型 */
    private Integer lastMsgType;

    /** 最后一条消息时间，会话列表的主排序依据 */
    private LocalDateTime lastMsgTime;

    public boolean isSingle() {
        return type != null && type == ConvType.SINGLE.getCode();
    }

    public boolean isGroup() {
        return type != null && type == ConvType.GROUP.getCode();
    }
}
