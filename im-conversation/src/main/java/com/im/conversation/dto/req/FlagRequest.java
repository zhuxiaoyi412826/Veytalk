package com.im.conversation.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 布尔开关入参，置顶与免打扰两个端点共用。
 */
@Data
@Schema(description = "开关入参")
public class FlagRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "true 开启，false 关闭", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "开关值不能为空")
    private Boolean enabled;
}
