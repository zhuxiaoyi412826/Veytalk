package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 主播端上报「这一场已经有回放了」。
 *
 * <p>数据全部来自推流端（Electron 主进程的上传台账）：清单是它 PUT 的，只有它知道到底有几片、
 * 每片多长。后端<b>没有中立数据源可以校对</b>（媒体服务器只有 PUT/GET，列不了目录），
 * 所以服务端只做<b>夹取</b>不做校验：{@code im.live.replay.max-duration-hours} 之外的值按上限取。
 * 这些数字只用于展示，多报一点不会让人多看到一秒画面，也不影响任何资金与权限。
 */
@Data
@Schema(description = "上报直播回放")
public class LiveReplayRequest {

    @NotNull(message = "分片数不能为空")
    @Schema(description = "回放清单里的分片数（1 秒一片）")
    private Integer segmentCount;

    @Schema(description = "回放总时长（秒），由分片时长累加")
    private Long durationSeconds;

    @Schema(description = "分片总字节数，供「约多少 MB」展示")
    private Long bytes;
}
