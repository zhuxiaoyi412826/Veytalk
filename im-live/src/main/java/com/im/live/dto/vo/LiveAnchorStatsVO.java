package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 主播数据面板：一次请求换回「概览 + 逐场 + 金主榜 + 礼物种类榜 + 本场实时」。
 *
 * <p><b>口径必须写清楚，否则数字会骗人</b>：
 * <ul>
 *   <li>礼物金额是<b>虚拟积分</b>，而且<b>不会进主播钱包</b>——送礼只扣送礼人
 *       （{@code GiftServiceImpl} 里只有 {@code walletSpi.deduct}，没有对主播的 credit），
 *       所以这里的「礼物价值」只是流水聚合出来的一个观感指标，不是收入、不能提现；</li>
 *   <li>点赞数与热度只在 Redis（{@code im:live:like:heat:{roomId}}，TTL 24 小时），
 *       <b>历史场次取不到点赞</b>，因此只有 {@link Current} 有点赞字段，逐场列表没有；
 *       弹幕条数服务端从来没有总量存储，本面板不提供；礼物两个位置都按流水聚合，
 *       不混用 Redis 热度（那个键只给 PK 用），免得同一个数出现两个值；</li>
 *   <li>时长由 {@code end_time - start_time} 现算，直播中的那一场用 {@code now - start_time}；</li>
 *   <li>概览是「时间窗内、最近 {@link #sessionLimit} 场」的和，不是账号历史全量——
 *       窗口内场次多于上限时 {@link #sessionsCapped} 为 true，前端要标出来，别让它看着像全量。</li>
 * </ul>
 *
 * <p>PK 战绩不在这里：会话与票数只存 Redis、按 {@code pkId} 寻址，没有「按主播」的索引，
 * 也没有超过保留期（默认 24 小时）的存档。要真做历史 PK 得建表，那是另一个决定。
 */
@Data
@Schema(description = "主播数据面板")
public class LiveAnchorStatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "统计窗口（天）")
    private Integer windowDays;

    @Schema(description = "概览与逐场最多取多少场（超出即截断，见 sessionsCapped）")
    private Integer sessionLimit;

    @Schema(description = "窗口内场次是否超过 sessionLimit（true 表示概览只覆盖了最近这些场，不是全量）")
    private Boolean sessionsCapped;

    @Schema(description = "概览指标")
    private Overview overview;

    @Schema(description = "逐场明细，按开播时间倒序")
    private List<Session> sessions;

    @Schema(description = "金主榜（按送礼人聚合，价值降序）")
    private List<Gifter> topGifters;

    @Schema(description = "礼物种类榜（按累计价值降序）")
    private List<GiftType> topGiftTypes;

    @Schema(description = "此刻正在直播那场的实时数据；没在播为 null")
    private Current current;

    /** 概览：窗口内那几场的合计 */
    @Data
    @Schema(description = "面板概览")
    public static class Overview implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "场次数（含直播中的那场）")
        private Integer totalSessions;

        @Schema(description = "累计直播时长（秒）")
        private Long totalDurationSeconds;

        @Schema(description = "累计观看人次（同一人反复进出会重复计）")
        private Integer totalViewer;

        @Schema(description = "平均峰值在线（按场简单平均，不加权时长）")
        private Integer avgPeakOnline;

        @Schema(description = "最高单场峰值在线")
        private Integer maxPeakOnline;

        @Schema(description = "累计礼物价值（虚拟积分，不进主播钱包、不可提现）")
        private Long totalGiftPrice;

        @Schema(description = "累计送礼笔数")
        private Long giftRecordCount;

        @Schema(description = "送礼人数（窗口内去重）")
        private Long giftSenderCount;

        @Schema(description = "最近一次开播时刻")
        private LocalDateTime lastLiveAt;
    }

    /** 逐场一行 */
    @Data
    @Schema(description = "单场直播明细")
    public static class Session implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "房间 ID（本场直播 ID）")
        private Long roomId;

        @Schema(description = "场次标题")
        private String title;

        @Schema(description = "封面图")
        private String cover;

        @Schema(description = "状态：1 直播中 2 已结束 3 已封禁")
        private Integer status;

        @Schema(description = "是否正在直播")
        private Boolean living;

        @Schema(description = "开播时间")
        private LocalDateTime startTime;

        @Schema(description = "关播时间，直播中为空")
        private LocalDateTime endTime;

        @Schema(description = "本场时长（秒），直播中为已播时长")
        private Long durationSeconds;

        @Schema(description = "峰值在线（关播定格值；直播中为本实例内存里的实时峰值）")
        private Integer peakOnline;

        @Schema(description = "累计观看人次")
        private Integer viewerTotal;

        @Schema(description = "本场礼物价值（虚拟积分）")
        private Long giftPrice;

        @Schema(description = "本场送礼笔数")
        private Long giftCount;

        @Schema(description = "本场送礼人数")
        private Long giftSenderCount;

        @Schema(description = "关播原因：stop 主动 / timeout 心跳超时 / ban 管理封禁，为空表示仍在直播")
        private String endReason;

        @Schema(description = "本场是否有回放可看（入口标记未过期）")
        private Boolean hasReplay;
    }

    /** 金主榜一行 */
    @Data
    @Schema(description = "送礼人聚合")
    public static class Gifter implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "送礼人用户 ID")
        private Long senderId;

        @Schema(description = "送礼人昵称（已注销或查不到为「未知用户」）")
        private String senderName;

        @Schema(description = "送礼人头像")
        private String senderAvatar;

        @Schema(description = "累计送出价值（虚拟积分）")
        private Long totalPrice;

        @Schema(description = "累计送礼笔数")
        private Long recordCount;

        @Schema(description = "最后一次送礼时刻")
        private LocalDateTime lastAt;
    }

    /** 礼物种类榜一行 */
    @Data
    @Schema(description = "礼物种类聚合")
    public static class GiftType implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "礼物 ID")
        private Long giftId;

        @Schema(description = "礼物名（流水里的冗余快照，可能是下架前的旧名）")
        private String giftName;

        @Schema(description = "礼物图标")
        private String giftIcon;

        @Schema(description = "累计个数")
        private Long totalCount;

        @Schema(description = "累计价值（虚拟积分）")
        private Long totalPrice;
    }

    /** 正在直播的那场：只有这里有点赞数，因为热度键只活 24 小时 */
    @Data
    @Schema(description = "本场实时数据")
    public static class Current implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "房间 ID")
        private Long roomId;

        @Schema(description = "当前在线人数（Redis 实时计数）")
        private Integer onlineCount;

        @Schema(description = "本场峰值在线（本实例内存统计，重启归零）")
        private Integer peakOnline;

        @Schema(description = "本场累计人次（本实例内存统计，重启归零）")
        private Integer viewerTotal;

        @Schema(description = "本场累计礼物价值（按流水聚合，与逐场列表同口径）")
        private Long giftPrice;

        @Schema(description = "本场累计点赞数（Redis 计数，TTL 24 小时；关播一天后即不可得）")
        private Long likeHeat;

        @Schema(description = "已播时长（秒）")
        private Long durationSeconds;
    }
}
