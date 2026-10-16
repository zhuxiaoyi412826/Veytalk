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
 * 消息投票选项记录，对应 {@code im_message_vote}。
 *
 * <p>一行代表「某用户对某条投票选了某个选项」，唯一键 {@code uk_msg_user_opt} 保证同一人对同一
 * 投票的同一选项至多一行。单选投票重新选择时先按 (message_id, user_id) 清空再写一条；多选按选项
 * 逐行存。与 {@link MessageReaction} 一样刻意不继承基类：本表只有 create_time。
 */
@Data
@TableName("im_message_vote")
public class MessageVote implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long messageId;

    /** 投票人 ID */
    private Long userId;

    /** 所选选项下标（从 0 开始） */
    private Integer optionIndex;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
