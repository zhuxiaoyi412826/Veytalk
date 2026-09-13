package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 群成员角色。
 */
@Getter
public enum GroupRole {

    /** 群主 */
    OWNER(1, "群主"),
    /** 管理员 */
    ADMIN(2, "管理员"),
    /** 普通成员 */
    MEMBER(3, "成员");

    @JsonValue
    private final int code;
    private final String desc;

    GroupRole(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static GroupRole of(Integer code) {
        if (code != null) {
            for (GroupRole role : values()) {
                if (role.code == code) {
                    return role;
                }
            }
        }
        return MEMBER;
    }

    /**
     * 是否具备群管理能力（群主或管理员）。
     */
    public boolean isManager() {
        return this == OWNER || this == ADMIN;
    }
}
