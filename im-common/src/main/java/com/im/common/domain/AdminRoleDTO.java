package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台可分配的角色（跨模块传递用的最简形状）。
 *
 * <p>为什么不在 im-admin 直接读 {@code im_role}：星型架构下这张表只在 im-user 映射一次，
 * 后台要「列出可分配的角色 / 看某人有哪些角色 / 给用户改角色」只能经 {@code AdminUserSpi} 把活交回 im-user。
 * 这里只带界面渲染需要的四个字段，不带 status/deleted 这些内部管理列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminRoleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色主键 */
    private Long id;

    /** 角色编码，如 admin / moderator / operator */
    private String roleCode;

    /** 角色中文名 */
    private String roleName;

    /** 角色说明 */
    private String description;
}
