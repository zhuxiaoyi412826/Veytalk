package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台远程审计页顶部的四个数字，由 im-remote 经 {@code AdminRemoteSpi} 提供。
 *
 * <p>刻意<b>没有</b>「今日高危动作数」这一项：它要按 {@code create_time} 范围扫
 * {@code im_remote_audit_log}，而这张表只有 {@code idx_session_time} 一条索引，
 * 且设计上只增不删、量级会一路涨上去。一个顶部数字不值得每次开页付一次全表扫，
 * 想做就先去补索引而不是先写代码——宁可少一个数字，也不要一个悄悄拖慢数据库的数字。
 *
 * <p>{@code generatedAt} 让页面能标注「数据截至」：本页的概览<b>不做自动轮询</b>
 * （没有缓存层挡着，轮询就是把 COUNT 直接打到库上），这个时间戳就是让人判断
 * 眼前的数字有多新、要不要手点一次刷新的唯一依据。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminRemoteOverviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** status=active 的会话数（状态机口径，走 idx_status） */
    private Long activeSessions;

    /** status=inviting 的会话数：等待授权的邀请，长期不为 0 说明有人在挂邀请 */
    private Long invitingSessions;

    /** 今日新建会话数（按 create_time 全表范围扫，im_remote_session 量级可控，见 SPI 注释） */
    private Long todaySessions;

    /** 此刻已认证且连接打开的 Agent 数（服务端内存注册表，不查库，单实例口径） */
    private Integer onlineAgents;

    /** 统计截止时刻 */
    private LocalDateTime generatedAt;
}
