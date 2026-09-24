package com.im.remote.ws;

import com.im.remote.service.RemoteRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 控制端（im-ui 浏览器 / Electron）的连接处理器。
 *
 * <p>与 Agent 侧不同，控制端连接天然是「会话级」的：握手票据已经绑定了 sessionId，
 * 所以连接建立即注册 {@link RemoteRelayService.ControlInfo}，第一条业务帧必须是
 * control-ready（消费票据、双向绑定）——在那之前所有其它帧都因无绑定被中继丢弃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlWebSocketHandler extends TextWebSocketHandler {

    private final RemoteRelayService relayService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        relayService.configureBuffers(session);
        Long userId = (Long) session.getAttributes().get(ControlHandshakeInterceptor.ATTR_USER_ID);
        String sid = (String) session.getAttributes().get(ControlHandshakeInterceptor.ATTR_SESSION_ID);
        String ticket = (String) session.getAttributes().get(ControlHandshakeInterceptor.ATTR_TICKET);
        if (userId == null || sid == null || ticket == null) {
            closeQuietly(session);
            return;
        }
        RemoteRelayService.ControlInfo control =
                relayService.registerControl(session, userId, Long.parseLong(sid), ticket);
        session.getAttributes().put(ControlHandshakeInterceptor.ATTR_CONTROL, control);
        log.info("控制端连接建立: userId={}, sessionId={}", userId, sid);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        RemoteRelayService.ControlInfo control = controlOf(session);
        if (control == null) {
            closeQuietly(session);
            return;
        }
        relayService.onControlText(control, message.getPayload());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        RemoteRelayService.ControlInfo control = controlOf(session);
        if (control == null) {
            closeQuietly(session);
            return;
        }
        byte[] raw = new byte[message.getPayloadLength()];
        message.getPayload().duplicate().get(raw);
        relayService.onBinary(false, session, raw);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RemoteRelayService.ControlInfo control = controlOf(session);
        if (control != null) {
            relayService.onControlClosed(control);
            log.info("控制端连接断开: sessionId={}, status={}", control.getSessionId(), status.getCode());
        }
    }

    private RemoteRelayService.ControlInfo controlOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ControlHandshakeInterceptor.ATTR_CONTROL);
        return value instanceof RemoteRelayService.ControlInfo control ? control : null;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
            // 关闭失败无副作用
        }
    }
}
