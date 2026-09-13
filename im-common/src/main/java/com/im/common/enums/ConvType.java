package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 会话类型。
 */
@Getter
public enum ConvType {

    /** 单聊 */
    SINGLE(1, "单聊"),
    /** 群聊 */
    GROUP(2, "群聊");

    @JsonValue
    private final int code;
    private final String desc;

    ConvType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ConvType of(Integer code) {
        if (code != null) {
            for (ConvType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
        }
        return SINGLE;
    }

    /**
     * 生成会话业务唯一键。单聊使用双方 ID 升序拼接，保证同一对用户只有一个会话。
     */
    public static String singleBizKey(Long userA, Long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return "s:" + min + ":" + max;
    }

    /**
     * 生成群聊会话业务唯一键。
     */
    public static String groupBizKey(Long groupId) {
        return "g:" + groupId;
    }
}
