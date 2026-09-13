package com.im.websocket.handler;

import com.im.websocket.manager.WsPrincipal;
import com.im.websocket.service.WsTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 握手拦截器：在 HTTP 升级成 WebSocket 之前完成鉴权。
 *
 * <p>鉴权必须放在这一层而不是 {@code afterConnectionEstablished}。握手阶段返回 {@code false}
 * 能直接给客户端一个 401，浏览器于是根本不建立连接；而等到连接建立后再拒绝，
 * 就得先接受升级、再主动关闭，客户端会先看到一次 {@code onopen} 紧接着一次 {@code onclose}，
 * 前端的重连逻辑很容易把这种「连上又被踢」误判成网络抖动而无限重试。
 *
 * <p>票据从查询参数取，而不是从请求头取：浏览器的 {@code WebSocket} 构造器不允许自定义请求头，
 * 这是浏览器端的硬限制，没有第二种选择。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    /** 握手查询参数名 */
    public static final String PARAM_TICKET = "ticket";

    /** 解析出的连接身份在握手属性里的存放键，会随属性一起进入 {@code WebSocketSession} */
    public static final String ATTRIBUTE_PRINCIPAL = "im.ws.principal";

    private final WsTicketService ticketService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = resolveTicket(request.getURI());
        WsPrincipal principal = ticketService.verifyQuietly(ticket);
        if (principal == null) {
            // verifyQuietly 内部已经记过具体原因（过期 / 签名不符 / 用途不符），这里只补一条访问日志
            log.warn("WebSocket 握手被拒绝: uri={}, remote={}",
                    request.getURI(), request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTRIBUTE_PRINCIPAL, principal);
        log.debug("WebSocket 握手通过: userId={}, deviceId={}, remote={}",
                principal.userId(), principal.deviceId(), request.getRemoteAddress());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("WebSocket 握手异常: uri={}", request.getURI(), exception);
        }
    }

    /**
     * 从查询串里取出 ticket。
     *
     * <p>手工切分而不用 {@code UriComponentsBuilder}：握手在每个连接的生命周期里只发生一次，
     * 但它是全站最高频被扫描的入口之一（未鉴权即可打到），这里越少分配对象越好。
     *
     * <p>做一次 URL 解码是防御性的：JWT 用的是 base64url 字母表，本身不含需要转义的字符，
     * 但个别代理或前端封装库会把 {@code .} 之外的符号再编码一层，
     * 不解码就会拿到一个签名校验必然失败的票据，而错误现象与「票据过期」完全一样，很难查。
     */
    private String resolveTicket(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || !PARAM_TICKET.equals(pair.substring(0, separator))) {
                continue;
            }
            String value = pair.substring(separator + 1);
            return value.isBlank() ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }
}
