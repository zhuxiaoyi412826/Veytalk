package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 「动态几天可见」的全局偏好，对应 {@code im_user_profile.moment_visible_days}。
 *
 * <p>它作用在<b>别人的历史动态</b>上：我设了三天，别人刷到我的动态时就只看得到最近三天发的，
 * 而不是「我只能在三天内看到别人的」。这一句必须写死在枚举上，因为它是这个字段最容易被实现反的地方。
 *
 * <p>取值就是天数本身（0/3/7/30），不再另编一套 code：这一列在库里存的、前端下拉框提交的、
 * 以及这里算 cutoff 用的是同一个数字，中间多一层映射只会多一处对不上的可能。
 */
@Getter
public enum MomentVisibleDays {

    /** 全部历史都可见（建表默认值） */
    ALL(0, "全部"),
    THREE_DAYS(3, "最近三天"),
    ONE_WEEK(7, "最近一周"),
    ONE_MONTH(30, "最近一个月");

    @JsonValue
    private final int code;
    /** 天数，与 code 同值；单独留一个名字是为了让 cutoff 那一句读起来是「减去天数」而不是「减去 code」 */
    private final int days;
    private final String desc;

    MomentVisibleDays(int days, String desc) {
        this.code = days;
        this.days = days;
        this.desc = desc;
    }

    /**
     * 按天数值取枚举。
     *
     * <p>★ null 与脏值一律退到 {@link #THREE_DAYS} 而不是 {@link #ALL}：这是隐私位，
     * 宁可少给。库里这一列是 {@code NOT NULL DEFAULT 0}，正常读不到脏值，
     * 真读到了说明有人手改过，那时候把三年前的动态全放出去是不可逆的泄露。
     */
    public static MomentVisibleDays of(Integer days) {
        if (days != null) {
            for (MomentVisibleDays item : values()) {
                if (item.days == days) {
                    return item;
                }
            }
        }
        return THREE_DAYS;
    }

    /** 展示文案；脏值退到「最近三天」，与 {@link #of} 同口径。 */
    public static String descOf(Integer days) {
        return of(days).desc;
    }

    /**
     * 早于这个时刻的动态对外不可见。
     *
     * @param now 调用方传进来的当前时间（自然时钟由服务端定，不接受客户端时间）
     * @return {@link #ALL} 返回 {@code null}，表示不设时间下界
     */
    public LocalDateTime cutoff(LocalDateTime now) {
        return this == ALL ? null : now.minusDays(days);
    }
}
