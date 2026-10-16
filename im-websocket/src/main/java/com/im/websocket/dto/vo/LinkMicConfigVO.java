package com.im.websocket.dto.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 连麦配置返回体：开关 + ICE 服务器清单，形状与 {@code CallConfigVO} 一致。
 *
 * <p>连麦与通话复用同一套 WebRTC ICE 下发，这里独立给一个端点是为了让连麦开关
 * （{@code im.linkmic.enabled}）能单独驱动前端是否加载连麦合成/连麦引擎代码。
 */
@Data
public class LinkMicConfigVO {

    /** 连麦总开关：false 时前端隐藏上麦/连麦入口，不再加载相关代码 */
    private boolean enabled;

    /** 单房间同时在麦嘉宾上限，前端据此渲染麦位数 */
    private int maxSlots;

    /** ICE 服务器清单，形状同 {@code CallConfigVO.iceServers}；关闭时为空 */
    private List<Map<String, Object>> iceServers;
}
