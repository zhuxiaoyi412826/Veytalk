package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 好友申请处理状态。
 */
@Getter
public enum RequestStatus {

    /** 待处理 */
    PENDING(0, "待处理"),
    /** 已同意 */
    ACCEPTED(1, "已同意"),
    /** 已拒绝 */
    REJECTED(2, "已拒绝"),
    /** 已过期 */
    EXPIRED(3, "已过期");

    @JsonValue
    private final int code;
    private final String desc;

    RequestStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按状态码反查枚举。
     *
     * @return 未匹配时返回 {@code null}
     */
    public static RequestStatus of(Integer code) {
        if (code != null) {
            for (RequestStatus value : values()) {
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
        RequestStatus value = of(code);
        return value == null ? "" : value.desc;
    }
}
