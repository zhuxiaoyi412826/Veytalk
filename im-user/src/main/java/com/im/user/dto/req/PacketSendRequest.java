package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发红包请求：单聊与群聊共用一个入口，差别由服务端按会话类型收敛。
 *
 * <p>{@code count} 在单聊里会被强制为 1（对方独占），传 10 也不会变成「对方能抢 10 次」；
 * 群聊里则夹到 {@code im.wallet.packet-max-count}，防止有人开出一个把钱包余额拆成上千份的红包。
 */
@Data
@Schema(description = "发送红包请求")
public class PacketSendRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "目标会话 ID（单聊或群聊）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;

    @Schema(description = "红包总额（积分，正数）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "红包金额不能为空")
    @Min(value = 1, message = "红包金额必须为正整数")
    private Long amount;

    @Schema(description = "份数，仅群聊有意义；为空按 1 处理")
    @Min(value = 1, message = "红包份数必须为正整数")
    private Integer count;

    @Schema(description = "祝福语，可空，默认「恭喜发财，大吉大利」")
    @Size(max = 30, message = "祝福语不能超过 30 个字符")
    private String greeting;
}
