package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 亲密度档位，按 {@code im_intimacy.score} 落在哪个区间决定。
 *
 * <p>档位放在枚举而不是一张字典表：它跟 {@code DatingEducation} / {@code DatingIncome} 是同一类东西——
 * 定长、有顺序、改一次就要连前端展示一起改。做成表只会多一个「运营能把 500 分改成 600 分」
 * 而前端文案与进度条口径不会跟着变的隐患。
 *
 * <p>{@link #minScore} 是<b>下界（含）</b>，档位之间不留空隙也不重叠：
 * 判定一律取「最后一个 {@code minScore <= score} 的档位」，所以新增档位只要插在合适的位置即可，
 * 不需要维护上界（维护上界就必须在改一档时同时改两档，漏一个就会出现「499 分显示两档」）。
 */
@Getter
public enum IntimacyLevel {

    /** 刚配上对，还没有多少来往 */
    STRANGER(1, 0, "初识"),
    /** 聊过一阵子 */
    FAMILIAR(2, 50, "熟悉"),
    /** 有来有回，火花不断 */
    CLOSE(3, 200, "亲近"),
    /** 长期高频互动 */
    TACIT(4, 500, "默契"),
    /** 顶档，只用来给人一个「到头了」的说法 */
    SOULMATE(5, 1000, "知心");

    @JsonValue
    private final int code;
    /** 进入本档所需的最低亲密度值（含） */
    private final int minScore;
    private final String desc;

    IntimacyLevel(int code, int minScore, String desc) {
        this.code = code;
        this.minScore = minScore;
        this.desc = desc;
    }

    /**
     * 按分值取档位。
     *
     * <p>负分与 null 一律落到第一档：分值只加不减，真出现负数说明库里被手改过，
     * 这时候返回 null 会让调用方到处判空，而「按最低档展示」不会误导任何人。
     */
    public static IntimacyLevel of(Integer score) {
        int value = score == null || score < 0 ? 0 : score;
        IntimacyLevel result = STRANGER;
        for (IntimacyLevel level : values()) {
            if (level.minScore <= value) {
                result = level;
            }
        }
        return result;
    }

    /** 展示文案；脏值与未匹配一律空串。 */
    public static String descOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (IntimacyLevel level : values()) {
            if (level.code == code) {
                return level.desc;
            }
        }
        return "";
    }

    /** 下一档；已是顶档返回 {@code null}（前端据此不再画进度条）。 */
    public IntimacyLevel next() {
        IntimacyLevel[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }
}
