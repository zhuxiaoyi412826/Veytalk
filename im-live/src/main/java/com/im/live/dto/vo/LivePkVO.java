package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 一场 PK 的对外快照：REST 返回它，弹幕通道也把它摊平成 {@code pk} 帧广播给两个房间。
 *
 * <p>热度与票数都是<b>算出来的</b>，不存字段：{@code LivePkService} 每次读 Redis 计数减基线再乘权重。
 * 定格值只在结算那一刻写回会话，用于事后回看。
 */
@Data
@Builder
@Schema(description = "连麦 PK 快照")
public class LivePkVO {

    @Schema(description = "PK 会话 ID")
    private String pkId;

    /** 0 进行中 / 1 时间到 / 2 提前结束，与 {@code LivePkPO} 的三个常量一致 */
    @Schema(description = "状态：0 进行中，1 已完成，2 已提前结束")
    private Integer status;

    @Schema(description = "本方主播（发起方）用户 ID")
    private Long anchorA;
    @Schema(description = "本方房间 ID")
    private Long roomA;
    @Schema(description = "本方主播昵称")
    private String nameA;
    @Schema(description = "本方主播头像")
    private String avatarA;

    @Schema(description = "对方主播用户 ID")
    private Long anchorB;
    @Schema(description = "对方房间 ID")
    private Long roomB;
    @Schema(description = "对方主播昵称")
    private String nameB;
    @Schema(description = "对方主播头像")
    private String avatarB;

    @Schema(description = "对局总时长（秒）")
    private Integer durationSeconds;
    @Schema(description = "剩余秒数，已结束时为 0")
    private Long remainSeconds;

    @Schema(description = "本方热度（时间窗内增量 × 权重 + 投票）")
    private Long heatA;
    @Schema(description = "对方热度")
    private Long heatB;
    @Schema(description = "本方票数")
    private Integer voteA;
    @Schema(description = "对方票数")
    private Integer voteB;
    @Schema(description = "本方热度占比（0~100），双方都为 0 时给 50，免得进度条一边倒")
    private Integer ratioA;

    @Schema(description = "胜方：A / B / DRAW，进行中为 null")
    private String winner;
    @Schema(description = "结束原因：time / manual / room-ended / banned")
    private String endReason;
}
