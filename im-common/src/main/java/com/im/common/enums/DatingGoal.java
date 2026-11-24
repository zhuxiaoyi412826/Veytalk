package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 交友目的，写进 {@code im_user_profile.dating_goal}。
 *
 * <p>这是滑动匹配与推荐流里权重最高的一项偏好：目的不一致的两个人刷到一起，
 * 后面的聊天基本不会发生，所以「认真交往」和「找活动搭子」必须是可以分开筛的两类人。
 */
@Getter
public enum DatingGoal {

    /** 未填写 */
    UNSET(0, "未填写"),
    SERIOUS(1, "认真交往"),
    TALK_FIRST(2, "先聊聊看"),
    FRIENDS(3, "交朋友"),
    ACTIVITY_PARTNER(4, "找线下活动搭子");

    @JsonValue
    private final int code;
    private final String desc;

    DatingGoal(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}。 */
    public static DatingGoal of(Integer code) {
        if (code != null) {
            for (DatingGoal value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；未填与脏值一律空串。 */
    public static String descOf(Integer code) {
        DatingGoal value = of(code);
        return value == null || value == UNSET ? "" : value.desc;
    }
}
