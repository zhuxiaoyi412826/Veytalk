package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改群机器人入参。字段为 {@code null} 表示不改（与 {@code UpdateGroupRequest} 语义一致）。
 */
@Data
@Schema(description = "修改群机器人入参")
public class UpdateRobotRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "机器人名称，传 null 不改")
    @Size(max = 64, message = "机器人名称长度不能超过 64")
    private String name;

    @Schema(description = "机器人头像 URL，空串表示清空")
    @Size(max = 512, message = "头像地址长度不能超过 512")
    private String avatar;

    @Schema(description = "用途说明，空串表示清空")
    @Size(max = 255, message = "用途说明长度不能超过 255")
    private String description;

    @Schema(description = "启用状态：true 启用 false 停用，传 null 不改")
    private Boolean enabled;
}
