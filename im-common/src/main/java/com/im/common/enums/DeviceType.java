package com.im.common.enums;

import lombok.Getter;

/**
 * 登录设备类型，用于 Sa-Token 多端登录管理与踢下线。
 */
@Getter
public enum DeviceType {

    WEB("web", "浏览器"),
    PC("pc", "桌面客户端"),
    ANDROID("android", "安卓"),
    IOS("ios", "iOS"),
    MINI("mini", "小程序");

    private final String code;
    private final String desc;

    DeviceType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DeviceType of(String code) {
        if (code != null) {
            for (DeviceType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
        }
        return WEB;
    }

    public static String codeOf(String code) {
        return of(code).getCode();
    }
}
