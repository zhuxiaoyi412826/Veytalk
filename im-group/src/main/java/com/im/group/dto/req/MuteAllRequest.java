package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 全员禁言开关入参。
 *
 * <p>开启后群主与管理员仍可发言，普通成员发言会被 6008 拦下；
 * 已经生效的单人禁言不受影响，两者相互独立。
 */
@Data
@Schema(description = "全员禁言开关入参")
public class MuteAllRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "true 开启全员禁言，false 关闭", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "全员禁言开关不能为空")
    private Boolean muteAll;
}
