package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 滑动发生在哪个入口，写进 {@code im_match_like.source} 与 {@code im_match_pair.like_source}。
 *
 * <p>两个用途：运营看「哪个入口的成对率高」，以及配对成功后那句会话系统提示要说什么
 * （在附近的人里成的对，提示写成「推荐流」就是把话编错了）。
 *
 * <p>取值与建表注释一一对应，<b>不要复用成自增序号</b>：这串数字已经写进过种子数据与后台报表口径，
 * 插一个值进去会让历史行的来源全部错位。
 */
@Getter
public enum MatchSource {

    /** 推荐流（滑动匹配页的默认来源） */
    RECOMMEND(1, "推荐流"),
    /** 附近的人 */
    NEARBY(2, "附近的人"),
    /** 动态 */
    MOMENT(3, "动态"),
    /** 访客「谁看过我」 */
    VISITOR(4, "访客"),
    /** 共同标签引出的同好列表 */
    TAG(5, "标签同好");

    @JsonValue
    private final int code;
    private final String desc;

    MatchSource(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}。 */
    public static MatchSource of(Integer code) {
        if (code != null) {
            for (MatchSource value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；脏值与未匹配一律空串，让调用方自己决定要不要退回到默认说法。 */
    public static String descOf(Integer code) {
        MatchSource value = of(code);
        return value == null ? "" : value.desc;
    }
}
