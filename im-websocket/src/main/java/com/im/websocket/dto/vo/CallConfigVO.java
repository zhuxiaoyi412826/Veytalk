package com.im.websocket.dto.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 通话配置返回体，供前端建 {@code RTCPeerConnection} 时填 {@code iceServers}。
 */
@Data
public class CallConfigVO {

    /** 通话总开关：false 时前端禁用全部通话入口，不再加载 WebRTC 相关代码 */
    private boolean enabled;

    /**
     * ICE 服务器清单，形状与 WebRTC 的 RTCIceServer 一致：{@code {urls:[...], username?, credential?}}。
     * 内网可只含 STUN 或为空（host 直连足够）；配了 TURN 时额外含一条带临时凭据的条目。
     */
    private List<Map<String, Object>> iceServers;
}
