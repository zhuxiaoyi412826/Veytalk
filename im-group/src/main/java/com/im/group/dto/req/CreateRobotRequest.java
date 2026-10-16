package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建群机器人入参。仅群主与管理员可操作，权限判定在服务层完成。
 */
@Data
@Schema(description = "创建群机器人入参")
public class CreateRobotRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "目标群 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "群 ID 不能为空")
    private Long groupId;

    @Schema(description = "机器人名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "机器人名称不能为空")
    @Size(max = 64, message = "机器人名称长度不能超过 64")
    private String name;

    @Schema(description = "机器人头像 URL，可空")
    @Size(max = 512, message = "头像地址长度不能超过 512")
    private String avatar;

    @Schema(description = "用途说明")
    @Size(max = 255, message = "用途说明长度不能超过 255")
    private String description;
}
