package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 用量护栏的顶部概览（四期第六批）：付费能力今天整体烧掉了多少。
 *
 * <p>「今日」一律以数据库 {@code CURDATE()} 为口径（与面试概览同一理由：只该有一个
 * 权威时钟）。{@code guardEnabled}/{@code trialSeconds} 回的是<b>当前运行态</b>，
 * 让管理员先看清「现在到底限没限」再看数字——护栏被关掉时，用量暴涨不是异常而是必然。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAiOverviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 护栏总开关（运行态） */
    private Boolean guardEnabled;

    /** 非 admin 累计预算（秒，运行态） */
    private Long trialSeconds;

    /** 全历史调用次数 */
    private Long totalCalls;

    /** 今日调用次数 */
    private Long todayCalls;

    /** 全历史扣预算耗时（毫秒，只含成功调用） */
    private Long totalMs;

    /** 今日耗时（毫秒，含失败） */
    private Long todayMs;

    /** 用过 AI 的去重人数（全历史） */
    private Long activeUsers;

    /** 今日用过 AI 的去重人数 */
    private Long todayActiveUsers;

    /** 被永久封禁 AI 功能的账号数 */
    private Long bannedUsers;

    /** 烧钱榜前几名（按累计耗时倒序） */
    private List<TopUser> topUsers;

    /** 数字生成时刻 */
    private LocalDateTime generatedAt;

    /** 烧钱榜一行 */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopUser implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long userId;
        private String userName;
        /** 累计扣预算耗时（毫秒） */
        private Long totalMs;
        /** 累计调用次数 */
        private Long calls;
    }
}
