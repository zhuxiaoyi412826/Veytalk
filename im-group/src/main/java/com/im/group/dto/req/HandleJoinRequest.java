package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审批入群申请入参。
 */
@Data
@Schema(description = "审批入群申请入参")
public class HandleJoinRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "true 通过、申请人立即入群；false 拒绝", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "approve 不能为空")
    private Boolean approve;
}
