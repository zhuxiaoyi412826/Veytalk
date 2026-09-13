package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单人禁言入参。
 *
 * <p>禁言与解除禁言共用一个接口，用 {@code muted} 区分：解除时 {@code minutes} 无意义，
 * Service 层会直接忽略，避免调用方为了解禁还得凑一个时长参数。
 */
@Data
@Schema(description = "单人禁言入参")
public class MuteMemberRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 无限期禁言时写入 mute_end_time 的分钟数上限，约 100 年 */
    private static final int MAX_MINUTES = 60 * 24 * 365 * 100;

    @Schema(description = "true 禁言，false 解除禁言", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "禁言开关不能为空")
    private Boolean muted;

    @Schema(description = "禁言时长（分钟），为空表示无限期禁言，解除禁言时忽略此字段", example = "60")
    @Min(value = 1, message = "禁言时长至少 1 分钟")
    @Max(value = MAX_MINUTES, message = "禁言时长过长")
    private Integer minutes;
}
