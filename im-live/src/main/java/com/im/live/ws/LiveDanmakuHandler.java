package com.im.live.ws;

import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.JsonUtil;
import com.im.live.config.LiveProperties;
import com.im.live.entity.LiveRoom;
import com.im.live.service.LiveDanmakuService;
import com.im.live.service.LiveRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 弹幕连接处理器：观众与主播共用一条端点。
 *
 * <p>连接建立即进房（{@link LiveDanmakuService#join}），断开即离房。收发都是轻量 JSON 文本帧，
 * 不落库、不发号、断线即忘——这是弹幕区别于 IM 消息的根本，理由见 {@link LiveDanmakuService} 类注释。
 *
 * <p>发言人身份在<b>连接建立时一次性解析并缓存进 session 属性</b>，而不是每条弹幕查一次：
 * 昵称/头像在一场直播里不会变，进房时取一次够用；主播标记（染色气泡）靠比对
 * {@code userId == anchorId}，anchorId 也在那一刻从房间实体拿到并缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveDanmakuHandler extends TextWebSocketHandler {

    private static final String ATTR_ROOM_ID = LiveHandshakeInterceptor.ATTR_ROOM_ID;
    private static final String ATTR_USER_ID = LiveHandshakeInterceptor.ATTR_USER_ID;
    private static final String ATTR_NICKNAME = "im.live.nickname";
    private static final String ATTR_AVATAR = "im.live.avatar";
    private static final String ATTR_ANCHOR = "im.live.anchor";

    private final LiveDanmakuService danmakuService;
    private final LiveRoomService roomService;
    private final UserQuerySpi userQuerySpi;
    private final LiveProperties properties;
    private final JsonUtil jsonUtil;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long roomId = (Long) session.getAttributes().get(ATTR_ROOM_ID);
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (roomId == null || userId == null) {
            closeQuietly(session);
            return;
        }
        LiveRoom room = roomService.findLiving(roomId);
        if (room == null) {
            // 握手到这里之间可能刚好关播：给一帧明确的系统提示再断，好过前端对着一个静默关闭的连接猜
            danmakuService.sendTo(session, danmakuService.frame(LiveDanmakuService.TYPE_SYSTEM, "content", "直播已结束"));
            closeQuietly(session);
            return;
        }
        boolean anchor = room.managedBy(userId);
        UserBriefDTO user = userQuerySpi.getById(userId);
        session.getAttributes().put(ATTR_ANCHOR, anchor);
        session.getAttributes().put(ATTR_NICKNAME, user == null ? "观众" : safeName(user));
        session.getAttributes().put(ATTR_AVATAR, user == null ? "" : nullToEmpty(user.getAvatar()));

        int online = danmakuService.join(roomId, session);
        if (room.getNotice() != null && !room.getNotice().isBlank()) {
            danmakuService.sendTo(session, danmakuService.frame(LiveDanmakuService.TYPE_NOTICE, "content", room.getNotice()));
        }
        // join 已广播过在线人数，这里补一帧只发给本人的欢迎，省一次前端往返
        danmakuService.sendTo(session, danmakuService.frame(LiveDanmakuService.TYPE_ONLINE, "count", online));
        log.debug("弹幕进房: roomId={}, userId={}, anchor={}, online={}", roomId, userId, anchor, online);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long roomId = (Long) session.getAttributes().get(ATTR_ROOM_ID);
        if (roomId == null) {
            return;
        }
        Map<String, Object> frame = jsonUtil.toMap(message.getPayload());
        Object typeValue = frame.get("type");
        String type = typeValue == null ? "" : String.valueOf(typeValue);
        switch (type) {
            case LiveDanmakuService.TYPE_DANMAKU -> onDanmaku(session, roomId, frame);
            case LiveDanmakuService.TYPE_LIKE -> onLike(session, roomId, frame);
            case LiveDanmakuService.TYPE_PING ->
                    danmakuService.sendTo(session, danmakuService.frame(LiveDanmakuService.TYPE_PONG, "ts", System.currentTimeMillis()));
            default -> {
                // 未知帧忽略即可：弹幕通道不需要严格协议，向前兼容比拒绝更重要
            }
        }
    }

    private void onDanmaku(WebSocketSession session, Long roomId, Map<String, Object> frame) {
        Object contentValue = frame.get("content");
        String content = contentValue == null ? "" : String.valueOf(contentValue).trim();
        if (content.isEmpty()) {
            return;
        }
        int max = properties.getDanmakuMaxLength();
        if (content.length() > max) {
            danmakuService.sendTo(session,
                    danmakuService.frame(LiveDanmakuService.TYPE_ERROR, "content", "弹幕最多 " + max + " 字"));
            return;
        }
        if (!danmakuService.allowDanmaku(session)) {
            danmakuService.sendTo(session,
                    danmakuService.frame(LiveDanmakuService.TYPE_ERROR, "content", "发送太快了，歇一下"));
            return;
        }
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        String nickname = (String) session.getAttributes().get(ATTR_NICKNAME);
        String avatar = (String) session.getAttributes().get(ATTR_AVATAR);
        boolean anchor = Boolean.TRUE.equals(session.getAttributes().get(ATTR_ANCHOR));
        danmakuService.broadcastDanmaku(roomId, userId, nickname, avatar, anchor, content);
    }

    private void onLike(WebSocketSession session, Long roomId, Map<String, Object> frame) {
        // count 缺省按 1 处理；夹在 [1, 50] 防单帧灌一大笔把合并计数打爆
        int count = 1;
        Object raw = frame.get("count");
        if (raw instanceof Number number) {
            count = number.intValue();
        }
        count = Math.max(1, Math.min(count, 50));
        danmakuService.addLikes(roomId, count);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        danmakuService.leave(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 传输层错误只记日志并摘除连接，不重抛：抛出会让容器把整条连接的关闭流程走两遍
        log.debug("弹幕连接传输错误: sessionId={}, {}", session.getId(), exception.toString());
        danmakuService.leave(session);
        closeQuietly(session);
    }

    private String safeName(UserBriefDTO user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername() == null ? "观众" : user.getUsername();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (Exception ignored) {
            // 关闭失败无副作用，容器随后会回调 afterConnectionClosed
        }
    }
}
