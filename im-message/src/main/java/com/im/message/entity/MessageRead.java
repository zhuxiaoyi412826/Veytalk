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
 * 消息送达与已读回执，对应 {@code im_message_read}。
 *
 * <p>一条消息对一个接收者只有一行（唯一键 {@code uk_msg_user}），送达与已读两个时间点共用一行，
 * 先写 {@code delivered_time}，后补 {@code read_time}，避免两张表各存一半状态。
 */
@Data
@TableName("im_message_read")
public class MessageRead implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long messageId;

    /** 接收者 ID */
    private Long userId;

    private LocalDateTime deliveredTime;

    private LocalDateTime readTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
