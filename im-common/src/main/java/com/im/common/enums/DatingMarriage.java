package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 婚恋状况，写进 {@code im_user_profile.marriage}。
 *
 * <p>这一列不做大小排序的筛选语义（「不低于某状态」是无意义的比较），所以只要求取值合法。
 *
 * <p>{@code 4 不愿透露} 与 {@code 0 未填} 是两回事：前者是用户主动选择，资料卡上要显示成
 * 「不愿透露」；后者是还没填，整行不显示。把它们混成一个 0，等于把「我不想说」读成「他没说清」。
 */
@Getter
public enum DatingMarriage {

    /** 未填写 */
    UNSET(0, "未填写"),
    UNMARRIED(1, "未婚"),
    DIVORCED_SINGLE(2, "单身离异"),
    WIDOWED(3, "丧偶"),
    PRIVATE(4, "不愿透露");

    @JsonValue
    private final int code;
    private final String desc;

    DatingMarriage(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}。 */
    public static DatingMarriage of(Integer code) {
        if (code != null) {
            for (DatingMarriage value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；只有「未填」与脏值给空串，{@code 不愿透露} 要如实显示。 */
    public static String descOf(Integer code) {
        DatingMarriage value = of(code);
        return value == null || value == UNSET ? "" : value.desc;
    }
}
