package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 开关入群审批入参。
 */
@Data
@Schema(description = "开关入群审批入参")
public class JoinApprovalRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "true 开启入群审批（通过链接/二维码进入需管理员确认）；false 关闭", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "joinApproval 不能为空")
    private Boolean joinApproval;
}
