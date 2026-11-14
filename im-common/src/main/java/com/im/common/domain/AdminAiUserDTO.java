package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 用量的按人聚合行（四期第六批）：后台「谁在烧钱」主视图的一行。
 *
 * <p>聚合在实现方一条 {@code GROUP BY user_id} 里完成，{@code budgetMs}/{@code remainingMs}
 * 是装配时按<b>当前</b>配置算出来的快照——管理员中途调预算，这一行的剩余量不会回头改历史行，
 * 它反映的是「此刻看这个人还剩多少」。
 *
 * <p>{@code unlimited=true} 表示该账号不受护栏约束（admin 角色，或护栏总开关已关），
 * 前端据此把剩余量显示成「—」而不是一个虚假的巨大数字。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAiUserDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 展示名（昵称优先，兜底账号名） */
    private String userName;

    /** 累计调用次数（含失败，失败也是真实发生过的事） */
    private Long totalCalls;

    /** 成功调用次数 */
    private Long successCalls;

    /** 累计扣预算的模型耗时（毫秒，只含成功调用） */
    private Long totalMs;

    /** 今日（数据库时钟口径）耗时（毫秒，含失败，看活跃度用） */
    private Long todayMs;

    /** 最近一次调用时间 */
    private LocalDateTime lastUsedTime;

    /** 当前配置下该账号的预算（毫秒） */
    private Long budgetMs;

    /** 剩余预算（毫秒），已超支为 0；unlimited 时无意义 */
    private Long remainingMs;

    /** 是否不受护栏约束 */
    private Boolean unlimited;

    /** 是否已被封禁 AI 功能 */
    private Boolean banned;

    /** 封禁原因（未封禁为 null） */
    private String banReason;
}
