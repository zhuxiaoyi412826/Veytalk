package com.im.websocket.config;

import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.websocket.handler.ImWebSocketHandler;
import com.im.websocket.handler.WsHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 装配：注册端点、握手拦截器，以及容器级的缓冲区与空闲超时。
 *
 * <h2>为什么 setAllowedOriginPatterns("*") 在这里是安全的</h2>
 *
 * <p>放开 Origin 通常意味着跨站 WebSocket 劫持（CSWSH）风险：恶意页面在受害者浏览器里
 * 发起握手，浏览器自动带上目标站的 Cookie，服务端一看有登录态就放行，攻击者于是能收发受害者的消息。
 * 这条攻击链的前提是<strong>凭证会由浏览器自动携带</strong>，而本项目正好把前提拆掉了：
 * <ul>
 *   <li>{@code sa-token.is-read-cookie=false}，系统压根不存在 Cookie 登录态；</li>
 *   <li>握手凭证是 URL 上的短时票据，票据得先调 {@code POST /api/ws/ticket} 换取，
 *       那个接口要求 {@code satoken} 请求头，而跨站页面的 fetch 带不上这个头
 *       （它不在 CORS 允许列表里，预检就被挡了）。</li>
 * </ul>
 * 所以第三方页面无论如何也拿不到一张有效票据，放开 Origin 换不来任何攻击面。
 * 反过来，若这里改成白名单，生产环境把前后端部署到同一个新域名时，
 * 握手会因为 Origin 不在 {@code im.cors.allowed-origins} 里而全部失败——
 * 那是一个只在上线当天才暴露、且报错信息（HTTP 403）完全指不到根因的坑。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ImWebSocketHandler webSocketHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;
    private final ImProperties imProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, ImConstants.WS_ENDPOINT)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * 容器级参数。这些值必须在握手发生前就设好，运行期改不了，所以走 Bean 而不是每次连接时配置。
     *
     * <p>文本缓冲区抬到 {@code im.websocket.max-text-message-bytes}（默认 64KB）：
     * Tomcat 默认只有 8KB，而一条 5000 字的中文消息 UTF-8 编码后是 15KB，
     * 默认值下它会以 {@code TOO_BIG} 关闭连接，服务端日志里连一条业务异常都看不到。
     *
     * <p>二进制缓冲区反而压到最小：本协议只走 JSON 文本，{@code TextWebSocketHandler}
     * 会拒绝二进制帧。留一个小缓冲是为了让超大二进制尽早触发 {@code TOO_BIG}，
     * 而不是先把内存吃掉再拒绝。
     *
     * <p>容器空闲超时设成心跳超时的两倍，作为最后一道兜底。正常回收由
     * {@code WsIdleCheckTask} 在 {@code heartbeatTimeoutSeconds} 上做——那条路径能记日志、
     * 能主动摘除注册表。容器超时存在的意义是：万一那个定时任务因为线程池耗尽或异常而停摆，
     * 死连接仍然会被 Tomcat 自己收掉，不会无限堆积到耗尽文件描述符。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ImProperties.Websocket config = imProperties.getWebsocket();
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(config.getMaxTextMessageBytes());
        container.setMaxBinaryMessageBufferSize(8 * 1024);
        container.setMaxSessionIdleTimeout(config.getHeartbeatTimeoutSeconds() * 2000L);
        return container;
    }
}
