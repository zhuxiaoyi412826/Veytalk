package com.im.websocket.handler;

import com.im.common.api.Result;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.websocket.dispatch.WsInboundDispatcher;
import com.im.websocket.manager.WsConnection;
import com.im.websocket.manager.WsPrincipal;
import com.im.websocket.service.WsPresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/**
 * WebSocket 容器适配层。
 *
 * <p>本类的职责被刻意压到最薄：接管容器回调、给会话套上并发安全的装饰器、绑定日志上下文，
 * 然后把帧原样交给 {@link WsInboundDispatcher}。任何业务判断都不写在这里，
 * 因为容器回调栈上的异常语义与业务代码里的完全不同——这里漏出一个异常等于直接断连。
 *
 * <p>继承 {@link TextWebSocketHandler} 而不是直接实现 {@code WebSocketHandler}，
 * 顺带拿到一条免费的防护：它会对二进制帧回一个 {@code NOT_ACCEPTABLE} 并关闭连接。
 * 本协议只走 JSON 文本，若自己实现就得手写这段拒绝逻辑，漏了的话二进制帧会静默丢弃，
 * 客户端以为发出去了，服务端什么也没收到，两端日志都是干净的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImWebSocketHandler extends TextWebSocketHandler {

    private final WsPresenceService presenceService;
    private final WsInboundDispatcher dispatcher;
    private final ImProperties imProperties;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WsPrincipal principal = (WsPrincipal) session.getAttributes()
                .get(WsHandshakeInterceptor.ATTRIBUTE_PRINCIPAL);
        if (principal == null) {
            // 握手拦截器保证了这个属性必然存在，走到这里说明有人绕过拦截器直接注册了 handler
            log.error("WebSocket 会话缺少连接身份，拒绝建立: sessionId={}", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        ImProperties.Websocket config = imProperties.getWebsocket();
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, config.getSendTimeLimitMs(), config.getSendBufferSizeBytes());
        WsConnection connection = new WsConnection(decorated, principal);
        // 存进装饰后的会话即可：装饰器把 getAttributes() 委派给原始会话，两者读到的是同一个 map
        decorated.getAttributes().put(WsConnection.ATTRIBUTE_KEY, connection);

        bindMdc(connection);
        try {
            presenceService.onConnected(connection);
        } catch (RuntimeException e) {
            // 走到这里连接已经注册成功、身份也是合法的，出问题的只可能是上线后的某个副作用
            // （刷在线键、补推离线消息）。断掉它换来的只会是客户端重连后同样的失败，
            // 所以记完整堆栈、保持连接，让用户至少还能正常收发消息
            log.error("WebSocket 上线流程异常，连接保持: userId={}, deviceId={}",
                    principal.userId(), principal.deviceId(), e);
        } finally {
            clearMdc();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WsConnection connection = WsConnection.from(session);
        if (connection == null) {
            // 没有身份的帧无从判断权限，继续收下去只是在给一个来历不明的连接提供服务
            log.warn("收到无身份的 WebSocket 帧，关闭连接: sessionId={}", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        // 收到任何帧都算活跃，不只是 ping：客户端在正常发消息就说明连接是通的
        connection.touch();
        bindMdc(connection);
        try {
            dispatcher.dispatch(connection, message.getPayload());
        } finally {
            clearMdc();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        WsConnection connection = WsConnection.from(session);
        if (connection != null) {
            bindMdc(connection);
        }
        try {
            log.warn("WebSocket 传输错误: userId={}, sessionId={}, err={}",
                    connection == null ? null : connection.userId(), session.getId(),
                    exception.getMessage());
            // 传输层已经出错，这条连接不可能再恢复。先摘除再关闭，
            // 因为部分容器实现在异常路径上不会再触发 afterConnectionClosed
            if (connection != null) {
                presenceService.onDisconnected(connection, CloseStatus.SERVER_ERROR);
            }
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        } finally {
            clearMdc();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WsConnection connection = WsConnection.from(session);
        if (connection == null) {
            return;
        }
        bindMdc(connection);
        try {
            // 重复调用是安全的：unregister 用身份比对的带值删除，
            // 摘不下来的第二次调用什么都不会做，也不会误伤同设备位上的新连接
            presenceService.onDisconnected(connection, status);
        } catch (RuntimeException e) {
            log.error("WebSocket 下线流程异常: userId={}, deviceId={}",
                    connection.userId(), connection.deviceId(), e);
        } finally {
            clearMdc();
        }
    }

    /**
     * 绑定日志上下文。
     *
     * <p>{@code TraceIdFilter} 是 Servlet 过滤器，只覆盖 HTTP 请求。WebSocket 升级完成后
     * 帧的处理不再经过过滤器链，若不在这里补一手，{@code logback-spring.xml} 里的
     * {@code %X{traceId}} 和 {@code %X{userId}} 会全部输出空值——
     * 长连接相关的日志量恰恰是全系统最大的，缺了这两个字段基本没法排查单个用户的问题。
     */
    private void bindMdc(WsConnection connection) {
        // 每个帧一个独立 traceId，与 HTTP 侧「一次请求一个」的粒度保持一致
        MDC.put(Result.TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
        MDC.put(ImConstants.MDC_USER_ID, String.valueOf(connection.userId()));
    }

    private void clearMdc() {
        MDC.remove(Result.TRACE_ID);
        MDC.remove(ImConstants.MDC_USER_ID);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception e) {
            log.debug("关闭 WebSocket 会话失败: sessionId={}, err={}", session.getId(), e.getMessage());
        }
    }
}
