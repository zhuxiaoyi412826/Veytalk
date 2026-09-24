package com.im.remote.config;

import com.im.common.constant.ImConstants;
import com.im.remote.ws.AgentHandshakeInterceptor;
import com.im.remote.ws.AgentWebSocketHandler;
import com.im.remote.ws.ControlHandshakeInterceptor;
import com.im.remote.ws.ControlWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 远程控制的两条 WebSocket 端点装配。
 *
 * <p>与聊天通道（im-websocket 的 {@code /ws}）刻意分开：那条通道承载 JSON 消息协议，
 * 容器二进制缓冲被压到 8KB；而中继要过几十 KB 的屏幕块与文件分块，
 * 两条通道的缓冲区策略、心跳语义、鉴权凭证都不同，合在一个端点上只会互相牵制。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 的安全性论证与 {@code WebSocketConfig} 一致：
 * 系统没有 Cookie 登录态，控制端握手的 ticket 只能由已登录用户经 REST 换取，
 * 跨站页面拿不到 satoken 头也就拿不到 ticket。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
public class RemoteWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentHandler;
    private final ControlWebSocketHandler controlHandler;
    private final AgentHandshakeInterceptor agentInterceptor;
    private final ControlHandshakeInterceptor controlInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentHandler, ImConstants.WS_REMOTE_AGENT_ENDPOINT)
                .addInterceptors(agentInterceptor)
                .setAllowedOriginPatterns("*");
        registry.addHandler(controlHandler, ImConstants.WS_REMOTE_CONTROL_ENDPOINT)
                .addInterceptors(controlInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
