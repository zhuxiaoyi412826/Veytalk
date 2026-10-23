package com.im.websocket.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 群通话（多人）信令载荷，上行 {@code groupcall} 与下行 {@code groupcall-signal} 共用一个类。
 *
 * <p>为什么不复用 {@link WsCallPayload}：那套形状是「一主一被」的，全局只有一个 {@code calleeUserId}，
 * 而群通话的每一帧协商都要指明「发给房间里哪一位」（{@link #toUserId}），成员是一份
 * {@link #members} 花名册而不是两个字段。把它们塞进同一个类，等于让
 * {@code CallSignalingService} 的 switch 同时面对两种会话形状（与连麦当年另立 {@code linkAction}
 * 是同一个判断：宁可多一个类型，也不让已经跑通的分支被改写）。
 *
 * <p>各 action 只用到其中一部分字段，合法性（callId 是否存在、发送方是不是本群成员、
 * 房间有没有满）全部在 {@code GroupCallSignalingService} 里判，不依赖这里的字段约束。
 *
 * <ul>
 *   <li>{@code create} —— {@link #groupId} + {@link #mediaType} + {@link #calleeUserIds}</li>
 *   <li>{@code invite} —— {@link #callId} + {@link #calleeUserIds}（通话进行中拉人，上限内截断）</li>
 *   <li>{@code join} / {@code reject} / {@code leave} —— {@link #callId}（reject/leave 另带 {@link #reason}）</li>
 *   <li>{@code offer} / {@code answer} / {@code ice} —— {@link #callId} + {@link #toUserId} + SDP 或 candidate</li>
 *   <li>{@code state} —— {@link #callId} + {@link #muted} + {@link #cameraOff}</li>
 * </ul>
 */
@Data
public class WsGroupCallPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 信令子类型。上行只有这九个值会被受理：
     * create / invite / join / reject / leave / offer / answer / ice / state；
     * 下行还会多出 roster / member-join / member-leave / failed / end。
     */
    private String action;

    /** 通话 ID，由发起人（create）生成的 uuid，同一次群通话的所有信令共享 */
    private String callId;

    /** 所属群 ID：成员资格的判定依据，也是「同一群同时只开一路群通话」的天然键 */
    private Long groupId;

    /** 成员 ID 清单：{@code create} 为首批邀请，{@code invite} 为通话进行中追加邀请（服务端会去掉自己、非成员与超上限的部分） */
    private List<Long> calleeUserIds;

    /**
     * 这一帧转发给房间里的哪一位成员，仅 offer / answer / ice 使用。
     * 服务端校验双方都在花名册里，然后按该成员登记过的 deviceId 精确投递——
     * mesh 拓扑下每人有 N-1 路 PeerConnection，广播式转发会让每个候选都被发给所有人。
     */
    private Long toUserId;

    /** 媒体类型：audio / video，create 时定下，整场通话沿用 */
    private String mediaType;

    /** SDP 类型：offer / answer */
    private String sdpType;

    /** SDP 原文 */
    private String sdp;

    /**
     * ICE 候选，前端 {@code RTCPandidate.toJSON()} 的形状（{candidate, sdpMid, sdpMLineIndex}）。
     * 用 Object 原样透传：服务端不理解其内部结构，只负责搬运。
     */
    private Object candidate;

    /** 静音状态，仅 {@code state} 使用；null 表示本帧不改这一项 */
    private Boolean muted;

    /** 摄像头状态，仅 {@code state} 使用；null 表示本帧不改这一项（语音通话恒为 null） */
    private Boolean cameraOff;

    /** 挂断 / 拒绝 / 被踢原因，可选，用于前端文案（如 user-leave / max-duration / full） */
    private String reason;

    /**
     * 信令发起方用户 ID，仅下行由服务端按连接身份填充，客户端上行填了也会被丢弃。
     * 接收端据此知道「这条 offer 是房间里的谁发来的」，并以此 as PeerConnection 的键。
     */
    private Long fromUserId;

    /**
     * 花名册，仅下行的 {@code roster} / {@code member-join} 携带。
     *
     * <p>{@code roster} 只发给刚进房的人（它需要知道该向谁发起 offer）；{@code member-join}
     * 只带新来的那一名（其余人据此知道要多建一路 PeerConnection）。同一个字段两种用法，
     * 省掉再开一个 {@code member} 字段。
     */
    private List<GroupCallMember> members;

    /**
     * 花名册条目。字段刻意很少：它是「谁在房间里、处于什么状态」的服务端权威视图，
     * 昵称与头像由前端从群成员列表里取（前端本就加载过，没必要在每帧信令里重复搬运）。
     */
    @Data
    public static class GroupCallMember implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long userId;

        /**
         * 该成员接听那台设备的 deviceId，PENDING 期为 null。
         * 下行中继帧按它精确投递；不发给前端也无妨，前端拿到只是多一个看不懂字段，
         * 而「同一人多设备」的问题必须有个地方记录，否则会出现「手机接听、电脑也在收 offer」。
         */
        private String deviceId;

        /** PENDING（已邀请未接听）/ ACTIVE（已进房，媒体可协商） */
        private String status;

        private Boolean muted;

        private Boolean cameraOff;

        /** 进房时间戳（毫秒），用于前端排序与「谁最后加入」的展示 */
        private long joinedAt;
    }
}
