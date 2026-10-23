package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台可分配角色的一行，用于「配角色」下拉。
 *
 * <p>形状与 {@code AdminRoleDTO} 几乎一致，但仍走一层 VO 而不是把 SPI 的 DTO 直接吐给前端：
 * 一是保持「REST 返回值都是本模块的 VO」这一惯例（DTO 属于跨模块契约，不该出现在接口签名上），
 * 二是以后想在角色行上加派生列（如「当前有几个用户持有该角色」）时有地方放，而不用回改 SPI 契约。
 */
@Data
@Schema(description = "后台可分配角色")
public class AdminRoleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "角色主键")
    private Long id;

    @Schema(description = "角色编码，如 admin / moderator / operator")
    private String roleCode;

    @Schema(description = "角色中文名")
    private String roleName;

    @Schema(description = "角色说明")
    private String description;
}
