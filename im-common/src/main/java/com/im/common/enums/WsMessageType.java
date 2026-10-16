package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * WebSocket 报文类型。
 */
@Getter
public enum WsMessageType {

    /* ---------- 上行（客户端 -> 服务端） ---------- */
    /** 发送聊天消息 */
    CHAT("chat"),
    /** 消息已送达上报 */
    DELIVERED("delivered"),
    /** 消息已读上报 */
    READ("read"),
    /** 撤回消息 */
    RECALL("recall"),
    /** 心跳 */
    PING("ping"),
    /** 拉取离线消息 */
    PULL_OFFLINE("pull-offline"),
    /** 音视频通话信令上行（invite/accept/reject/offer/answer/ice/cancel/hangup），子类型见 data.action */
    CALL("call"),
    /** 直播连麦信令上行（request/accept/reject/offer/answer/ice/leave），子类型见 data.linkAction，载荷复用 call 的形状并带 roomId */
    LINKMIC("linkmic"),

    /* ---------- 下行（服务端 -> 客户端） ---------- */
    /** 发送结果回执，携带服务端消息 ID 与 seq */
    ACK("ack"),
    /** 心跳响应 */
    PONG("pong"),
    /** 新消息推送 */
    MESSAGE("message"),
    /** 送达状态推送给发送方 */
    DELIVERED_NOTIFY("delivered-notify"),
    /** 已读状态推送给发送方 */
    READ_NOTIFY("read-notify"),
    /** 撤回通知 */
    RECALL_NOTIFY("recall-notify"),
    /** 编辑通知：发送者改了自己已发出的文本消息后推给会话全员，各端气泡实时刷新正文并标「已编辑」 */
    EDIT_NOTIFY("edit-notify"),
    /** 表情回应变更通知：某人添加/取消某条消息的一个表情后推给会话全员，各端气泡表情栏据此增量刷新 */
    REACTION_NOTIFY("reaction-notify"),
    /** 投票变更通知：有人投 / 改票后推给会话全员，各端据服务端下发的权威票数刷新投票卡片 */
    VOTE_NOTIFY("vote-notify"),
    /** 精华消息变更通知：群主/管理员标记或取消精华后推给群成员，各端气泡的精华标签实时增减 */
    ESSENCE_NOTIFY("essence-notify"),
    /** 系统 / 业务通知（好友申请、入群等） */
    NOTIFY("notify"),
    /** 多端登录被踢下线 */
    KICKOUT("kickout"),
    /** 好友在线状态变更 */
    ONLINE_STATE("online-state"),
    /** 会话未读数变更 */
    UNREAD("unread"),
    /** 音视频通话信令下行：服务端在两端间转发信令，以及主动下发的 busy/offline/timeout/canceled */
    CALL_SIGNAL("call-signal"),
    /** 直播连麦信令下行：服务端在观众与主播间转发协商，以及申请到达、上麦成功、被拒/下麦等事件 */
    LINKMIC_SIGNAL("linkmic-signal"),
    /** 闪照已被查看并销毁的通知：任一成员首次查看后推给会话全员（含查看者自己的其他设备） */
    FLASH_NOTIFY("flash-notify"),
    /** 错误响应 */
    ERROR("error");

    @JsonValue
    private final String type;

    WsMessageType(String type) {
        this.type = type;
    }

    public static WsMessageType of(String type) {
        if (type != null) {
            for (WsMessageType value : values()) {
                if (value.type.equalsIgnoreCase(type)) {
                    return value;
                }
            }
        }
        return null;
    }
}
