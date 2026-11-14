package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一次表态：对某个 code 的某个 version 同意还是拒绝。
 *
 * <p>注册与登录后的再同意弹窗都用它，区别只在服务端补的 {@code source} 字段。
 *
 * <p><b>{@code version} 是必填的</b>：客户端不能只说「我同意了隐私政策」，必须说清同意的是哪一版。
 * 这一条决定了两种完全不同的失败——没交某一份是 {@code AGREEMENT_REQUIRED}，
 * 交的是旧版号是 {@code AGREEMENT_VERSION_MISMATCH}（文本在他阅读期间被换过，
 * 前端必须重新展示新内容再取一次同意，绝不能拿他对着旧文本点的那个勾去留痕）。
 *
 * <p>{@code accept} 允许为空，空按「同意」解释：注册页那个勾选框只有勾上才会把这一项发过来，
 * 强制弹窗的「同意并继续」也不带这个字段。只有可选的服务条款会显式发 {@code false}，
 * 那条要留一份「他明确拒过」的证据。
 */
@Data
@Schema(description = "协议同意项")
public class AgreementAcceptItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "协议标识：terms 用户协议 / privacy 隐私政策 / service 服务条款",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "privacy")
    @NotBlank(message = "协议标识不能为空")
    private String code;

    @Schema(description = "所同意的版本号，取自协议列表接口，必须仍是当前生效版",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "协议版本号不能为空")
    @Min(value = 1, message = "协议版本号从 1 开始")
    private Integer version;

    @Schema(description = "是否同意，缺省视为同意；只有可选条款会显式传 false 以留下拒签记录")
    private Boolean accept;

    /** 是否算「同意」：accept 未表态时按同意处理 */
    public boolean accepted() {
        return accept == null || accept;
    }
}
