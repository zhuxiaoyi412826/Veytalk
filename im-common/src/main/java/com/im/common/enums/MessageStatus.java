package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 消息状态，前端气泡右下角展示。
 */
@Getter
public enum MessageStatus {

    /** 发送中，仅客户端本地状态 */
    SENDING(0, "发送中"),
    /** 已发送（服务端已落库） */
    SENT(1, "已发送"),
    /** 已送达对方 */
    DELIVERED(2, "已送达"),
    /** 对方已读 */
    READ(3, "已读"),
    /** 已撤回 */
    RECALLED(4, "已撤回"),
    /** 发送失败 */
    FAILED(5, "发送失败");

    @JsonValue
    private final int code;
    private final String desc;

    MessageStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
