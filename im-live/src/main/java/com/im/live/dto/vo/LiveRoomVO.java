package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 直播间展示视图。
 *
 * <p>与实体的关键差别有两处：
 * <ol>
 *   <li><b>不含 streamKey</b>——它是推流/播放目录的能力凭证，只在开播响应里给主播一次；</li>
 *   <li>{@code playUrl} 是<b>已签名</b>的地址，且只在直播中才填。签名带有效期，
 *       所以这个 VO 不能被前端长期缓存，进房时现取现用。</li>
 * </ol>
 */
@Data
@Schema(description = "直播间信息")
public class LiveRoomVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "房间 ID（本场直播 ID）")
    private Long id;

    @Schema(description = "主播用户 ID")
    private Long anchorId;

    @Schema(description = "主播昵称")
    private String anchorName;

    @Schema(description = "主播头像")
    private String anchorAvatar;

    @Schema(description = "房间标题")
    private String title;

    @Schema(description = "封面图")
    private String cover;

    @Schema(description = "房间公告")
    private String notice;

    @Schema(description = "推流源：screen / camera")
    private String sourceType;

    @Schema(description = "分辨率档位")
    private String resolution;

    @Schema(description = "视频码率（kbps）")
    private Integer bitrateKbps;

    @Schema(description = "状态：1 直播中 2 已结束 3 已封禁")
    private Integer status;

    @Schema(description = "当前在线人数（取自 Redis 实时计数，非库值）")
    private Integer onlineCount;

    @Schema(description = "峰值在线")
    private Integer peakOnline;

    @Schema(description = "累计观看人次")
    private Integer viewerTotal;

    @Schema(description = "开播时间")
    private LocalDateTime startTime;

    @Schema(description = "关播时间，直播中为空")
    private LocalDateTime endTime;

    @Schema(description = "已签名的播放地址（m3u8），仅直播中返回")
    private String playUrl;

    @Schema(description = "弹幕 WebSocket 地址：直播中、或已结束聊天模式未超时时返回")
    private String danmakuWs;

    @Schema(description = "当前用户是否为该房间主播")
    private Boolean mine;
}
