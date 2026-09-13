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
    /** 系统 / 业务通知（好友申请、入群等） */
    NOTIFY("notify"),
    /** 多端登录被踢下线 */
    KICKOUT("kickout"),
    /** 好友在线状态变更 */
    ONLINE_STATE("online-state"),
    /** 会话未读数变更 */
    UNREAD("unread"),
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
