package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import lombok.Getter;

/**
 * 消息类型。
 */
@Getter
public enum MsgType {

    /** 文本消息 */
    TEXT(1, "文本"),
    /** 图片消息 */
    IMAGE(2, "图片"),
    /** 文件消息 */
    FILE(3, "文件"),
    /** 语音消息 */
    VOICE(4, "语音"),
    /** 系统通知，由服务端生成，客户端只读 */
    SYSTEM(5, "系统通知");

    @JsonValue
    private final int code;
    private final String desc;

    MsgType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static MsgType of(Integer code) {
        if (code == null) {
            throw new BusinessException(ResultCode.MESSAGE_TYPE_UNSUPPORTED);
        }
        for (MsgType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new BusinessException(ResultCode.MESSAGE_TYPE_UNSUPPORTED);
    }

    /**
     * 是否属于附件类消息（图片 / 文件 / 语音），此类消息 content 存 fileId。
     */
    public boolean isAttachment() {
        return this == IMAGE || this == FILE || this == VOICE;
    }
}
