package com.im.live.config;

import com.im.common.constant.ImConstants;
import com.im.live.ws.LiveDanmakuHandler;
import com.im.live.ws.LiveHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 直播弹幕 WebSocket 端点装配（{@code /ws/live}）。
 *
 * <p>与聊天通道（{@code /ws}）、远控通道（{@code /ws/remote/*}）都分开：三条通道的鉴权凭证、
 * 缓冲策略、消息语义各不相同——聊天要落库发号、远控要过几十 KB 二进制帧、弹幕是高频可丢弃文本，
 * 合在一个端点上只会互相牵制（理由详见 {@link ImConstants#WS_LIVE_ENDPOINT}）。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 的安全性论证与其余端点一致：系统不读 Cookie 登录态，
 * 握手凭证 satoken 只能由已登录前端主动挂在 URL 上，跨站页面拿不到它就握不上手。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
public class LiveWebSocketConfig implements WebSocketConfigurer {

    private final LiveDanmakuHandler danmakuHandler;
    private final LiveHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(danmakuHandler, ImConstants.WS_LIVE_ENDPOINT)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
