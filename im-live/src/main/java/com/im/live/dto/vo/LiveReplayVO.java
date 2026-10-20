package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回放视图：一个「看回放」的请求换回一份现签的地址与展示数据。
 *
 * <p>{@code playUrl} 与直播那样是<b>已签名</b>的，同样不能长期缓存——签名有效期就是
 * {@code im.live.sign-ttl-seconds}（默认 6 小时），所以每次点「看回放」都现取一次。
 * 清单里引用的分片不带签名（与直播侧口径一致，见 md/直播.md §10.1）：
 * 拿到清单就等于拿到了那场的 streamKey 目录，不必再签一遍。
 */
@Data
@Schema(description = "直播回放")
public class LiveReplayVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "房间 ID（本场直播 ID）")
    private Long roomId;

    @Schema(description = "已签名的回放清单地址（VOD m3u8），可拖进度条")
    private String playUrl;

    @Schema(description = "回放时长（秒）")
    private Long durationSeconds;

    @Schema(description = "分片数（1 秒一片）")
    private Integer segmentCount;

    @Schema(description = "分片总字节数")
    private Long bytes;

    @Schema(description = "上报（关播定版）时刻，毫秒时间戳")
    private Long reportAt;
}
