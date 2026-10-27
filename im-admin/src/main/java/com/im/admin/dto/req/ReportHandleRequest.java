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

    /**
     * 封禁时长（分钟），<b>只对 {@code ban_user} 有效</b>；不传就是永久封禁。
     *
     * <p>四期第三批加（封禁时长）。之所以不在这里把其它动作也收一个 duration：其它动作（警告 / 撤回 / 关播）
     * 没有「到期」这个概念，收一个会被静默忽略的参数比不收更糟——它会让人以为自己能设定时关播。
     * 其它动作传了值服务端也不报错也不读（见 {@code ReportService#executeRemote}），
     * 因为前端下拉切走时把旧值留在表单里是一个很常见的动作，为一个 UI 残留拒绝处置不值得。
     */
    @Schema(description = "封禁时长（分钟），仅 ban_user 读取；留空为永久封禁", example = "1440")
    private Long disableMinutes;
}
