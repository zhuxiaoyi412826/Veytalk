package com.im.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 群精华消息标记，对应 {@code im_message_essence}。
 *
 * <p>一行代表「某条消息被设为精华」，唯一键 {@code uk_message} 保证一条消息至多一行，取消精华即删行。
 * 精华是群维度的内容沉淀（需按 conversation_id 列出群内全部精华、并随消息列表批量判定某条是否精华），
 * extra JSON 列无法高效承担这两种反查，故与 Reaction / Vote 一样建独立表。
 *
 * <p>与 {@link MessageReaction}、{@link MessageVote} 一样刻意不继承基类：本表只有 create_time。
 */
@Data
@TableName("im_message_essence")
public class MessageEssence implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属群会话 ID，按会话聚合查询精华列表 */
    private Long conversationId;

    /** 被标记为精华的消息 ID */
    private Long messageId;

    /** 标记人 ID（群主 / 管理员） */
    private Long markUserId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
