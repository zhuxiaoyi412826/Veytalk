package com.im.websocket.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 在麦嘉宾条目，供连麦麦位列表渲染。
 */
@Data
@AllArgsConstructor
public class LinkMicGuestVO {

    /** 嘉宾用户 ID */
    private Long userId;

    /** 展示名（昵称优先，退化账号） */
    private String name;

    /** 头像地址，可空 */
    private String avatar;
}
