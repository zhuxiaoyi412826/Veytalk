package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 送礼请求入参。
 *
 * <p>只做形状校验（礼物 ID 非空、数量至少 1）；数量上界（{@code im.wallet.max-gift-count}）与礼物是否
 * 存在/下架、余额是否充足，都由服务层判定——这些依赖运行时配置与库存状态，不能写死在注解里。
 */
@Data
@Schema(description = "送礼请求")
public class SendGiftRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "礼物 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "礼物 ID 不能为空")
    private Long giftId;

    @Schema(description = "送礼数量，默认 1")
    @Min(value = 1, message = "送礼数量至少为 1")
    private Integer count = 1;
}
