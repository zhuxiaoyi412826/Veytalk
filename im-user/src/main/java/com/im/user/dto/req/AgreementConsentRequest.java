package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 登录后的再同意入参。
 *
 * <p>一次提交带<b>一组</b>而不是一个：强制弹窗会把所有待签项一起摊给用户（「用户协议 v2 与
 * 隐私政策 v2 已更新」），点一次「同意并继续」应当把两条留痕同时落库。拆成前端循环调 N 次
 * 单条接口，就会出现「协议记了、隐私政策没记上」的半态，而这种情况下的重试最难判。
 *
 * <p>没有 {@code source} 字段：能走到这个接口的只可能是登录态下的弹窗，场景由服务端钉死为
 * {@code dialog}，和找回密码那里的 {@code scene} 是同一条规矩——入口能证明的事，不让客户端声明。
 */
@Data
@Schema(description = "协议再同意请求")
public class AgreementConsentRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本次表态的协议项，至少一项", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "至少要提交一项协议表态")
    @Valid
    private List<AgreementAcceptItem> agreements;
}
