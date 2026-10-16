package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 充值请求入参。
 *
 * <p>这里只做「非空 + 正数」的基础形状校验；真正的上下界（{@code im.wallet.recharge-min/max}）
 * 由服务层按配置判定，因为界限可配、不能写死在注解里。
 */
@Data
@Schema(description = "钱包充值请求")
public class RechargeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本次充值到账的积分额", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "充值数额不能为空")
    @Min(value = 1, message = "充值数额必须为正整数")
    private Long amount;
}
