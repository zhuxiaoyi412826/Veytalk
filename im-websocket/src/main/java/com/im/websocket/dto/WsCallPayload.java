package com.im.websocket.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 音视频通话信令载荷，上行 {@code call} 与下行 {@code call-signal} 共用一个类。
 *
 * <p>与 {@code WsChatPayload} 同一取向：只声明客户端真正有权决定的字段，身份相关的
 * {@code fromUserId} 在转发时由服务端按连接身份覆写，绝不采信客户端填的值——
 * 否则任何人都能伪造「来电来自某用户」的信令。
 *
 * <p>各 action 只用其中一部分字段：
 * <ul>
 *   <li>{@code invite} —— {@link #calleeUserId} + {@link #mediaType}</li>
 *   <li>{@code accept}/{@code reject}/{@code cancel}/{@code hangup} —— {@link #callId}（hangup/cancel 另带 {@link #reason}）</li>
 *   <li>{@code offer}/{@code answer} —— {@link #callId} + {@link #sdpType} + {@link #sdp}</li>
 *   <li>{@code ice} —— {@link #callId} + {@link #candidate}（原样透传的 RTCIceCandidate JSON）</li>
 * </ul>
 * 合法性（callId 是否存在、发送方是否为该通话的当事人）全部在 {@code CallSignalingService} 里判，
 * 不依赖这里的字段约束。
 */
@Data
public class WsCallPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 信令子类型：invite / accept / reject / cancel / offer / answer / ice / hangup */
    private String action;

    /** 通话 ID，由主叫生成的 uuid，同一次通话的所有信令共享 */
    private String callId;

    /** 被叫用户 ID，仅 invite 使用 */
    private Long calleeUserId;

    /** 媒体类型：audio / video，invite 与 offer/answer 携带 */
    private String mediaType;

    /** SDP 类型：offer / answer */
    private String sdpType;

    /** SDP 原文 */
    private String sdp;

    /**
     * ICE 候选，前端 {@code RTCPandidate.toJSON()} 的形状（{candidate, sdpMid, sdpMLineIndex}）。
     * 用 Object 原样透传：服务端不理解其内部结构，只负责搬运，避免为易变的 WebRTC 内部字段建模。
     */
    private Object candidate;

    /** 挂断 / 取消原因，可选，用于前端文案（如 busy / timeout / user-hangup） */
    private String reason;

    /**
     * 信令发起方用户 ID，仅下行由服务端填充（取连接身份），客户端上行填了也会被覆盖。
     * 接收端据此知道「这条 offer/answer/hangup 是对端发来的」。
     */
    private Long fromUserId;

    /**
     * 连麦所属房间 ID，仅 {@code linkmic} 报文使用（{@code call} 报文为 null）。
     * 与 {@link #linkAction} 一起把通话信令的形状复用到连麦，服务端按 roomId 查房间态与主播。
     */
    private Long roomId;

    /**
     * 连麦信令子类型：request / accept / reject / offer / answer / ice / leave，仅 {@code linkmic} 使用。
     *
     * <p>与 {@link #action}（call 的子类型）分开：两套子类型词表不同，混用一个字段会让 switch 分支
     * 同时面对 call 与 linkmic 的 action，易漏判。连麦不复用 {@code action} 而另立此字段，就是为了让
     * {@code CallSignalingService} 的分支不被连麦语义污染。
     */
    private String linkAction;
}
