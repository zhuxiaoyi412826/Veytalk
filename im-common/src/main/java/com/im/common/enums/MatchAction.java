package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 滑动动作，写进 {@code im_match_like.action}。
 *
 * <p>跳过也要落行而不是删行：推荐流要按「不再出现」过滤候选人，不落跳过就只能靠曝光时间衰减去猜，
 * 用户划过的人会反复回来。撤销喜欢同理是<b>把这一行改成 2</b>（{@code uk_from_to} 下一个方向只有一行），
 * 这样「他曾经喜欢过我」这个信号还能留给推荐流降权用。
 */
@Getter
public enum MatchAction {

    /** 喜欢 */
    LIKE(1, "喜欢"),
    /** 跳过（含「把已经点过的喜欢收回去」） */
    PASS(2, "跳过");

    @JsonValue
    private final int code;
    private final String desc;

    MatchAction(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}（调用方据此拒掉非法入参，不静默当成跳过）。 */
    public static MatchAction of(Integer code) {
        if (code != null) {
            for (MatchAction value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }
}
