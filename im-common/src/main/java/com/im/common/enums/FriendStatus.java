package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 好友关系状态（单向记录，每个用户各持一行）。
 */
@Getter
public enum FriendStatus {

    /** 正常好友 */
    NORMAL(1, "正常"),
    /** 已将对方拉黑 */
    BLOCKED(2, "已拉黑");

    @JsonValue
    private final int code;
    private final String desc;

    FriendStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按状态码反查枚举。
     *
     * @return 未匹配时返回 {@code null}
     */
    public static FriendStatus of(Integer code) {
        if (code != null) {
            for (FriendStatus value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 按状态码取中文描述，用于列表回显。
     */
    public static String descOf(Integer code) {
        FriendStatus value = of(code);
        return value == null ? "" : value.desc;
    }
}
