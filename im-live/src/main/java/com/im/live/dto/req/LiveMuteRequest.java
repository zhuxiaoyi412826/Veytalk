package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 禁言请求。
 *
 * <p>{@code minutes} 语义：正数为分钟数（5 / 30 / 60 这类预设），<b>0 或负数表示永久禁言</b>。
 * 上界不在注解里写死而由服务层夹，因为「永久」这条分支本身就绕过了上界。
 *
 * <p>{@code roomId} 可选：带上当下这场房间时，服务端顺手给那个人的在线连接推一帧提示，
 * 让他立刻知道自己被禁了，而不是下次发弹幕才发现。不传则只在下次发言时生效。
 */
@Data
@Schema(description = "禁言请求")
public class LiveMuteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "被禁言用户 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    @Schema(description = "禁言分钟数，0 或负数表示永久；缺省 5")
    private Integer minutes = 5;

    @Schema(description = "当前房间 ID（可选，仅用于即时通知）")
    private Long roomId;
}
