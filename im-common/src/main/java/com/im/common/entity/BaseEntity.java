package com.im.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 实体基类：在 {@link AuditEntity} 的时间戳之上增加逻辑删除字段。
 *
 * <p>适用于用户、角色、权限、群组、文件等需要保留历史记录的主数据表；
 * 关联表与流水表（无 {@code deleted} 列）请改继承 {@link AuditEntity}。
 *
 * <p>各业务实体的主键自行声明 {@code @TableId(type = IdType.ASSIGN_ID)}，
 * 由 MyBatis-Plus 雪花算法生成，避免自增主键在分库分表场景下的局限。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class BaseEntity extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 逻辑删除标记：0 未删除 1 已删除，序列化时对客户端隐藏 */
    @JsonIgnore
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
