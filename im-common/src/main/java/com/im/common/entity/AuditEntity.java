package com.im.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计基类：只包含创建时间与更新时间，<b>不含逻辑删除列</b>。
 *
 * <p>好友关系、好友申请、会话成员、群成员、消息投递记录等表采用物理删除——
 * 删好友、退群、删消息本身就是业务动作，记录必须真正消失，唯一键才能立刻释放
 * （例如 {@code uk_user_friend} 释放后同一对用户才能重新申请加好友）。
 *
 * <p>这些表没有 {@code deleted} 列，因此不能继承 {@link BaseEntity}，
 * 否则 MyBatis-Plus 会为不存在的列拼出 {@code deleted = 0} 条件，直接导致 SQL 报错。
 */
@Data
public abstract class AuditEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
