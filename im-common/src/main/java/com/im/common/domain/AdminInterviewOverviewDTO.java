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
 * 后台面试屏顶部的概览数字与违规榜，由 im-ai 经 {@code AdminInterviewSpi#overview()} 给。
 *
 * <p>{@code staleOngoing} 是这一屏唯一「拿来就能采取行动」的数字：进行中的场次
 * 是靠 {@code InterviewAuditService} 的巡检收尾的，但刷新/断电留下的 {@code status=0}
 * 行在巡检跑过之前会一直挂着。管理员看到它在涨就知道有人在半途中途弃考（或巡检没在跑），
 * 而看到 {@code ongoing} 单独变大才是「此刻真的有人在考试」。两个数分开给，
 * 合成一个「进行中」反而谁都读不出结论。
 *
 * <p>{@code topViolators} 是一次 {@code GROUP BY user_id} 的现算，全表扫。
 * 面试场次的量级由「报岗位的人」决定，与消息表不同阶，所以现在不加缓存；
 * 且这一屏<b>不做轮询</b>（手点刷新）。榜只取前 10 名，条数在服务端夹死。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminInterviewOverviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 累计面试场次 */
    private Long totalSessions;

    /** 今日新建场次（自然日 0 点起） */
    private Long todaySessions;

    /** 当前 {@code status=0} 的场次 */
    private Long ongoingSessions;

    /** 其中开始时间早于 2 小时的进行中场次，疑似掉线残留（口径由实现方夹死） */
    private Long staleOngoing;

    /** 因违规达阈值被强制结束的场次数（全历史） */
    private Long violationLimitSessions;

    /** 今日被强制结束的场次数 */
    private Long todayViolationLimit;

    /** 违规场次的占比（0~1，分母是已结束场次），保留两位小数由前端做 */
    private Double violationRate;

    /** 违规最多的候选人榜，最多 10 行 */
    private List<TopViolator> topViolators;

    /** 生成时刻 */
    private LocalDateTime generatedAt;

    /** 违规榜的一行 */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopViolator implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 候选人 ID */
        private Long userId;

        /** 候选人昵称 */
        private String candidateName;

        /** 名下违规总次数（跨场次求和） */
        private Long violations;

        /** 名下场次总数——一个只考过一场就违规 8 次的人和一个考过 40 场的人不是同一件事 */
        private Long sessions;
    }
}
