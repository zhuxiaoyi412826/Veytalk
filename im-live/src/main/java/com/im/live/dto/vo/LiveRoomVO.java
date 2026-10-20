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

    /**
     * 本场是否有回放可看。不随带地址：签名地址有有效期，只能点「看回放」时现取
     * （{@code GET /api/live/{roomId}/replay}）。直播中的房间恒为 false——那时看的是直播本身。
     */
    @Schema(description = "本场是否有回放（true 时前端显示「看回放」）")
    private Boolean hasReplay;

    @Schema(description = "当前用户是否为该房间主播")
    private Boolean mine;

    /**
     * 门禁信息：公开房 {@code type=none}；密码/付费房给前端渲染解锁弹窗。
     * 与 {@link #playUrl}/{@link #danmakuWs} 一致——未解锁时这两个为空，前端据此判断要不要拦一层。
     */
    @Schema(description = "门禁信息（none/password/paid）")
    private LiveGateVO gate;

    /**
     * 当前用户是否已拿到本房间观看权（公开/已解锁/主播本人均为 true）。
     * false 时 {@link #playUrl} 与 {@link #danmakuWs} 不随下发。
     */
    @Schema(description = "当前用户是否已解锁（true 才能拿到播放地址）")
    private Boolean unlocked;
}
