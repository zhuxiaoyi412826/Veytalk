package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 设置群内角色入参。
 *
 * <p>取值限定 2 / 3，即「设为管理员」与「取消管理员」。群主只能通过转让接口产生，
 * 如果放开 1，一次误调用就会让群里同时出现两个群主，而 {@code im_group.owner_id} 还停在旧值上。
 */
@Data
@Schema(description = "设置群内角色入参")
public class RoleRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "目标角色：2 管理员 3 普通成员", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "角色不能为空")
    @Min(value = 2, message = "只能设置为管理员或普通成员")
    @Max(value = 3, message = "只能设置为管理员或普通成员")
    private Integer role;
}
