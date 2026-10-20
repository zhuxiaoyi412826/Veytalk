package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 红包详情：点开气泡上的红包时现取，数据全部来自 Redis 领取态。
 *
 * <p>{@code status} 是给前端的一个总结论，省掉「先判过期再判领完再判自己领没领」的三段组合判断：
 * {@code unclaimed} 可领、{@code claimed} 已领过（显示我的那份）、{@code empty} 被领完、
 * {@code expired} 已过期退回（Redis 已清或已过 {@code expireAt}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "红包详情")
public class PacketVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String STATUS_UNCLAIMED = "unclaimed";
    public static final String STATUS_CLAIMED = "claimed";
    public static final String STATUS_EMPTY = "empty";
    public static final String STATUS_EXPIRED = "expired";

    @Schema(description = "红包 ID")
    private Long packetId;

    @Schema(description = "祝福语")
    private String greeting;

    @Schema(description = "总额（积分）")
    private long totalAmount;

    @Schema(description = "总份数")
    private int totalCount;

    @Schema(description = "已领份数")
    private int claimedCount;

    @Schema(description = "已被领走的金额")
    private long claimedAmount;

    @Schema(description = "当前用户在这个红包上的状态：unclaimed / claimed / empty / expired")
    private String status;

    @Schema(description = "当前用户已领到的金额，未领为 0")
    private long myAmount;

    @Schema(description = "过期时刻，前端据此显示倒计时")
    private long expireAt;

    @Schema(description = "领取明细，按抢到的先后排列")
    private List<Record> records;

    /**
     * 一条领取记录。
     *
     * <p>{@code seq} 是「第几个名额」（从 1 起）而不是自增主键：Redis Hash 的字段顺序不可依赖，
     * 明细要稳定按抢到的先后展示，就只能把序号写进领取记录里再排。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "红包领取记录")
    public static class Record implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "领取人用户 ID")
        private Long userId;

        @Schema(description = "领取人昵称快照")
        private String nickname;

        @Schema(description = "领取人头像")
        private String avatar;

        @Schema(description = "抢到的金额（积分）")
        private long amount;

        @Schema(description = "抢到的顺序，从 1 起")
        private int seq;
    }
}
