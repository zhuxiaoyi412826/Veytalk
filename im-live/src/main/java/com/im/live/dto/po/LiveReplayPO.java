package com.im.live.dto.po;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一场直播的回放元数据，以 JSON 存在 {@code im:live:replay:{roomId}} 里。
 *
 * <p><b>为什么没有对应的表，甚至为什么它不是回放的存储</b>：分片从推流那一刻起就落在媒体服务器上
 * （推流端 PUT 上去的，服务器只收不删，删除是另一个东西——媒体侧的按保留期清理任务），回放清单的路径又能由 roomId 确定性推导
 * （{@code {roomId}/replay/index.m3u8}）。所以这个 JSON 只提供两件事：前端「看回放」按钮的
 * <b>入口标记</b>，以及时长/体积这类<b>展示数据</b>。它丢了回放照样能播，只是没人找得到入口——
 * 为一个可以重建的标记建表，换来的是「表里的行比盘上的文件活得久」这种更难办的脏数据。
 *
 * <p>{@code ignoreUnknown}：这份 JSON 有保留期（默认 1 天），跨版本部署时旧键里的字段
 * 可能比新版少或多，读崩会让一场明明可播的回放显示成「没有回放」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LiveReplayPO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 属于哪一场直播（im_live_room.id） */
    private Long roomId;

    /** 上报者（主播）id：只在日志里用于追人，权限判定一律现查房间行 */
    private Long anchorId;

    /** 清单里的分片数（1 秒一片，等于「这场有多少秒画面进了回放」） */
    private Integer segmentCount;

    /** 回放总时长（秒），由分片 EXTINF 累加，与真实时长有百分比级偏差 */
    private Long durationSeconds;

    /** 分片总字节数，用于「约多少 MB」的展示 */
    private Long bytes;

    /** 上报时刻（毫秒）：入口排序与「几 days ago」的文案都用它，不查房间行是为了少一次 IO */
    private Long reportAt;
}
