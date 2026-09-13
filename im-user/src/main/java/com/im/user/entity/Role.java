package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 角色实体，对应表 {@code im_role}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_role")
public class Role extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色编码，如 admin / user */
    private String roleCode;

    private String roleName;

    private String description;

    /** 状态：1 启用 0 停用 */
    private Integer status;
}
