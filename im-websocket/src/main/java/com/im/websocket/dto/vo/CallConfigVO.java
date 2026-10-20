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

    /**
     * 群通话开关：只关多人不关单聊运维旋钮（{@code im.call.group-enabled}），
     * {@code enabled} 为 false 时本项必然也为 false。前端据此决定群聊里画不画「群通话」按钮。
     */
    private boolean groupEnabled;

    /**
     * 群通话人数上限（含发起人），下发的是服务端夹过 [2,6] 之后的真实值。
     * 前端拿它限制勾选人数，免得选了一堆却在 create 时被服务端默默截断。
     */
    private int groupMaxSlots;
}
