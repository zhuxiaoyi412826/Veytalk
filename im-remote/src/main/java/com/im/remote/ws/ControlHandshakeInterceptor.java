package com.im.remote.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.im.remote.config.RemoteProperties;
import com.im.remote.service.RemoteSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 控制端握手拦截器：校验一次性 ticket 的<strong>存在性与归属</strong>，但不消费。
 *
 * <p>为什么不在握手就消费票据：握手成功后连接仍可能立刻失败（代理截断、前端刷新），
 * 消费即焚会让一次失败的连接烧掉整个邀请，用户只能重新走一遍授权。
 * 真正的消费点在 control-ready 帧——「连接活着且客户端明确就绪」之后。
 * 60 秒 TTL 已经把「存在但未消费」的窗口压到与邀请超时同量级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "im.remote.userId";
    public static final String ATTR_SESSION_ID = "im.remote.sessionId";
    public static final String ATTR_TICKET = "im.remote.ticket";
    public static final String ATTR_CONTROL = "im.remote.control";

    private final RemoteProperties properties;
    private final RemoteSessionService sessionService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!properties.isEnabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        String ticket = AgentHandshakeInterceptor.resolveParam(request.getURI().toString(), "ticket");
        String sid = ticket == null ? null : sessionService.ticketSession(ticket);
        if (sid == null) {
            log.warn("控制端握手被拒绝: ticket 无效或已过期, remote={}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // 身份由「票据 ↔ 会话 ↔ inviter」链条在 control-ready 消费时锁定；
        // 握手阶段还要求登录态有效——否则拿到一个泄露的 ticket 字符串就能匿名接入
        Long userId = currentUserId(request);
        if (userId == null) {
            // 这条分支曾经完全静默：ticket 校验通过后 satoken 换不到登录态，直接回 401 且不留一行日志，
            // 现象是「控制端 WS 一连就上、半秒后被踢回设备列表」，与 ticket 过期、证书未信任都难以区分。
            // 明确记一条 WARN，把「没带 satoken / 换取失败(返回 null) / 校验抛异常」三种成因分开，见 currentUserId。
            log.warn("控制端握手被拒绝: satoken 登录态校验未通过, remote={}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_TICKET, ticket);
        attributes.put(ATTR_SESSION_ID, sid);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("控制端握手异常: uri={}", request.getURI(), exception);
        }
    }

    /**
     * 浏览器握手的登录态读取：WebSocket 升级请求不经过 Sa-Token 拦截器（那套只拦 /api/**），
     * 也带不了自定义请求头，所以前端在握手 URL 上额外挂一个 satoken 参数，
     * 服务端用它验一次登录态——票据只能换这条连接，token 泄露面不扩大。
     */
    private Long currentUserId(ServerHttpRequest request) {
        String token = AgentHandshakeInterceptor.resolveParam(request.getURI().toString(), "satoken");
        if (token == null || token.isBlank()) {
            log.warn("控制端握手未携带有效 satoken 参数, remote={}", request.getRemoteAddress());
            return null;
        }
        // 合法 JWT 恒为 base64url.header.payload.signature，绝不含空格；出现空格几乎必然是
        // 传输途中 '+' 被按 application/x-www-form-urlencoded 规则误解码成了 ' '（双重解码），
        // 会让 token 与 Redis 里的登录态对不上——单独记一条以便一眼定位该成因。
        if (token.indexOf(' ') >= 0) {
            log.warn("控制端 satoken 含空格(疑似 '+' 被误解码为空格), len={}", token.length());
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                log.warn("控制端 satoken 换取登录态为空(token 无效/已注销), len={}, segments={}",
                        token.length(), token.split("\\.").length);
            }
            return loginId == null ? null : Long.valueOf(String.valueOf(loginId));
        } catch (Exception e) {
            log.warn("控制端 satoken 校验抛异常: {}", e.toString());
            return null;
        }
    }
}
