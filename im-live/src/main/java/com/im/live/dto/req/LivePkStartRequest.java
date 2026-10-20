package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起一次连麦 PK。
 *
 * <p>只传对方主播 id：房间是「一场直播」的实例，服务端按 anchorId 现查他正在直播的那间房，
 * 让前端传 roomId 只会多一个「前端记着的房间可能已经不是现在这场」的错配机会。
 */
@Data
@Schema(description = "发起 PK")
public class LivePkStartRequest {

    @NotNull(message = "对手不能为空")
    @Schema(description = "对方主播用户 ID（必须是正在直播的主播，通常就是当前连麦的嘉宾）")
    private Long targetUserId;

    @Schema(description = "对局时长（秒），不传用默认值；超过上限按上限取")
    private Integer durationSeconds;
}
