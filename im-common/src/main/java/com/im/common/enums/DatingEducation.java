package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 学历，写进 {@code im_user_profile.education}。
 *
 * <p>用 TINYINT 而不是字符串：这一列要被筛选条件用 {@code >=} 比较（「本科及以上」），
 * 字符串比较出来的是字典序，「大专」会排在「本科」后面。
 *
 * <p>{@code 0} 是「未填」而不是「未知」：资料卡上未填的字段整行不展示，
 * 而「未知」会被读成用户填了一个答案。筛选与完资料度都按 {@code > 0} 判定。
 */
@Getter
public enum DatingEducation {

    /** 未填写 */
    UNSET(0, "未填写"),
    JUNIOR_AND_BELOW(1, "初中及以下"),
    HIGH_SCHOOL(2, "高中/中专"),
    COLLEGE(3, "大专"),
    BACHELOR(4, "本科"),
    MASTER(5, "硕士"),
    DOCTOR(6, "博士");

    @JsonValue
    private final int code;
    private final String desc;

    DatingEducation(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按 code 反查，未匹配返回 {@code null}——本枚举不猜「默认本科」。
     */
    public static DatingEducation of(Integer code) {
        if (code != null) {
            for (DatingEducation value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；未填与脏值都给空串，让前端整行留空而不是显示「未填写」这种系统口吻的字。 */
    public static String descOf(Integer code) {
        DatingEducation value = of(code);
        return value == null || value == UNSET ? "" : value.desc;
    }
}
