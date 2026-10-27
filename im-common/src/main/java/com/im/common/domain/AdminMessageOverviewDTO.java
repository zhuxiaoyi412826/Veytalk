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
 * 后台消息屏顶部的今日数字，由 im-message 经 {@code AdminMessageSpi#overview()} 给。
 *
 * <p><b>刻意不给「全站消息总数」</b>：{@code SELECT COUNT(*) FROM im_message} 在一张
 * 只增不清的表上是秒级的，而它每周期的唯一用途是让管理员看一个「只会变大」的数字。
 * 今日口径的两条聚合都带 {@code send_time >= ?}，正好走 {@code idx_send_time}，代价与当天量相关。
 * 真要看全站体量，那是 DBA 的 {@code information_schema} 或备份统计的事，不该由一个后台页面去数。
 *
 * <p>{@code expireDays} 与 {@code oldestRetainedTime} 是把「消息清理任务」这件事摆到台面上：
 * {@code MessageExpireCleanTask} 按 {@code im.message.retention-days} 物理删旧消息，
 * 管理员看到「今天删了多少」与「库里最早的一条是哪天」，才知道取证窗口有多长。
 * 没有这两列，「为什么查不到三个月前那条消息」就只能靠翻配置页猜。
 * 该配置项<b>默认为 0</b>，意思是永不清理——所以 {@code expireDays = 0} 不是 bug，
 * 而是「这张表只会被 {@code DELETE} 以外的方式缩小」这个事实的提醒。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMessageOverviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 今日落库消息条数 */
    private Long todayCount;

    /** 近 7 日消息条数（一次索引范围扫，不是七次今日计数） */
    private Long weekCount;

    /** 今日发送且已被撤回的条数（含用户自己撤与后台强制撤）；口径为何不用 recall_time 见 {@code AdminMessageSpi#overview()} */
    private Long todayRecalled;

    /** 今日后台强制撤回的条数，靠 force 分支写进 {@code extra} 的 {@code recalledByAdmin} 计数 */
    private Long todayAdminRecalled;

    /** 消息保留天数（{@code im.message.retention-days} 当前运行态，0 表示不清理） */
    private Integer expireDays;

    /** 库里现存最早一条消息的发送时间；空表为 null */
    private LocalDateTime oldestRetainedTime;

    /** 今日各类型的分布，按条数倒序，只给非零项 */
    private List<TypeSlice> typeSlices;

    /** 生成时刻 */
    private LocalDateTime generatedAt;

    /** 今日消息里某个类型占的份额 */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeSlice implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 类型码 1~14 */
        private Integer msgType;

        /** 类型中文名 */
        private String msgTypeDesc;

        /** 今日条数 */
        private Long count;
    }
}
