package com.im.live.dto.po;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一场连麦 PK 的完整会话态，以 JSON 存在 {@code im:pk:session:{pkId}} 里。
 *
 * <p><b>为什么没有对应的表</b>：PK 只在两个房间同时直播的那几分钟里存在，一方关播即作废；
 * 落库得到的是一堆没人回看的比赛记录，还要为它加清理任务。与连麦态（{@code im:link:state:*}）同构。
 *
 * <p>存的是<b>基线</b>而不是当前热度：热度是 Redis 计数的实时读数，抄一份进 JSON 就会和真值不一致。
 * 基线（开场那一刻的累计值）必须留着，否则先刷了十分钟热度再拉 PK 的主播一开场就赢。
 *
 * <p>{@code ignoreUnknown} 是为了这份 JSON 能加字段：会话只有几分钟寿命，但跨版本部署时
 * 旧 JSON 缺字段、新 JSON 多字段都可能出现，读崩会让正在进行的 PK 直接消失。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LivePkPO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 进行中的状态值（{@link #status}），前端据此决定进度条还走不走 */
    public static final int STATUS_RUNNING = 0;
    /** 时间到，按热度判胜负 */
    public static final int STATUS_FINISHED = 1;
    /** 有人提前结束/关播/被封，胜负仍按当时热度判，但 {@link #endReason} 说明原因 */
    public static final int STATUS_CANCELED = 2;

    /** 平局时 {@link #winner} 的取值 */
    public static final String WINNER_DRAW = "DRAW";

    private String pkId;

    /** {@code RUNNING / FINISHED / CANCELED}，见上面三个常量 */
    private Integer status;

    /** A 方主播（发起方） */
    private Long anchorA;
    /** A 方房间 */
    private Long roomA;
    /** A 方主播昵称快照，帧里要显示名牌，不为这个再回查用户模块 */
    private String nameA;
    private String avatarA;

    /** B 方主播 */
    private Long anchorB;
    private Long roomB;
    private String nameB;
    private String avatarB;

    /** 开场时刻（毫秒） */
    private Long startAt;
    /** 预定结束时刻（毫秒）= startAt + durationSeconds * 1000 */
    private Long endAt;
    private Integer durationSeconds;

    /** 开场那一刻 A 方的累计礼物热度，用于算增量 */
    private Long baseGiftA;
    /** 开场那一刻 A 方的累计点赞数 */
    private Long baseLikeA;
    private Long baseGiftB;
    private Long baseLikeB;

    /** 结算时定格的双方热度 */
    private Long heatA;
    private Long heatB;
    /** 结算时定格的双方票数 */
    private Integer voteA;
    private Integer voteB;
    /** 胜方 {@code A / B / DRAW}，未结算为 null */
    private String winner;
    /** 结束原因：time 时间到 / manual 主播提前结束 / room-ended 一方关播 / banned 一方被封 */
    private String endReason;
}
