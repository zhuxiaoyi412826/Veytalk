package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台处置举报工单入参。
 *
 * <p>{@code action} 是处置<b>结果</b>而不是状态：管理员说「我封了他」，
 * 由服务端推出 status=3（已处置）；驳回走另一个接口（{@code reject}），
 * 因为「驳回」意味着没动对象，把它做成一个 action 会让「action=none 但 status=3」
 * 这种组合在数据里说不清。
 *
 * <p>{@code remark} 是否必填由 {@code im.admin.handle-remark-required} 决定（默认必填），
 * 校验在 service 里做：这是运维口径而不是协议约束，改配置不该动代码。
 */
@Data
@Schema(description = "举报处置入参")
public class ReportHandleRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "处置动作：none / warn / ban_user / unban_user / recall_message / close_room",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请选择处置动作")
    private String action;

    @Schema(description = "处置说明，会回显给举报人并留档")
    private String remark;
}
