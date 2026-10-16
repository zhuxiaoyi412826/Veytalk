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
 * 消息表情回应记录，对应 {@code im_message_reaction}。
 *
 * <p>一行代表「某用户对某条消息贴了某个表情」，唯一键 {@code uk_msg_user_emoji} 保证同一人对
 * 同一条消息的同一表情至多一行；再点一次是取消（删除该行）。同一个人可以对一条消息贴多个不同表情。
 *
 * <p>与 {@link MessageDelete} 一样刻意不继承基类：本表只有 {@code create_time}，没有 {@code update_time}
 * 与 {@code deleted}，继承会让 MyBatis-Plus 往不存在的列自动填充从而报错。
 */
@Data
@TableName("im_message_reaction")
public class MessageReaction implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long messageId;

    /** 回应者 ID */
    private Long userId;

    /** 表情字符，如 👍 ❤️ 😄 */
    private String emoji;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
