package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 收入区间，写进 {@code im_user_profile.income}。
 *
 * <p>只存区间不存具体数字：收入是交友资料里最容易招来骚扰的一项，一个能按「月薪 18500」
 * 筛选的产品不是在帮人找对象，是在帮人筛钱包。
 *
 * <p>口径为税前月收入（人民币），文案里不写「月薪」两个字：区间边界已经足够清楚，
 * 而写死「月」会让「年终奖算不算」这种问题变成客服工单。
 */
@Getter
public enum DatingIncome {

    /** 未填写 */
    UNSET(0, "未填写"),
    BELOW_3K(1, "3千以下"),
    FROM_3K_TO_5K(2, "3千~5千"),
    FROM_5K_TO_10K(3, "5千~1万"),
    FROM_10K_TO_20K(4, "1万~2万"),
    FROM_20K_TO_50K(5, "2万~5万"),
    ABOVE_50K(6, "5万以上");

    @JsonValue
    private final int code;
    private final String desc;

    DatingIncome(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}。 */
    public static DatingIncome of(Integer code) {
        if (code != null) {
            for (DatingIncome value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；未填与脏值一律空串。 */
    public static String descOf(Integer code) {
        DatingIncome value = of(code);
        return value == null || value == UNSET ? "" : value.desc;
    }
}
