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
 * 消息单端删除记录，对应 {@code im_message_delete}。
 *
 * <p>「删除消息」只让消息对执行者本人不可见，其他成员照常看到，历史消息也不受影响——
 * 这与「撤回」是两件事：撤回对所有人失效且有 2 小时时限，删除没有时限且只作用于自己。
 */
@Data
@TableName("im_message_delete")
public class MessageDelete implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long messageId;

    /** 执行删除的用户 ID */
    private Long userId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
