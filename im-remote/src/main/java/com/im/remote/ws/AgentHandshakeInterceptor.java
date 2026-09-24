package com.im.remote.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.im.remote.config.RemoteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Agent 握手拦截器：token 可选的双模式入口。
 *
 * <p><b>账号模式</b>：带 Sa-Token 登录 token 握手，鉴权后属性里写入 userId，
 * 设备归属该账号（出现在它的「我的设备」列表）。
 *
 * <p><b>识别码模式（ToDesk 式）</b>：不带 token 直接握手——被控方无需注册账号，
 * 身份由后续 auth 帧里的识别码确立（userId 按 0 处理）。握手阶段不设卡不等于
 * 连接可信：未发 auth 的连接由巡检任务 10 秒回收；伪造识别码只能骗到自己发起的
 * 邀请（弹窗确认仍在被控端本地），不构成对他人设备的未授权接入。
 *
 * <p>带了 token 但无效则直接拒绝——不能把「token 过期」静默降级成匿名连接，
 * 否则账号模式的重连 bug 会伪装成「设备莫名变成识别码设备」，排障时极难察觉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "im.remote.userId";

    private final RemoteProperties properties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!properties.isEnabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        String token = resolveParam(request.getURI().toString(), "token");
        if (token == null || token.isBlank()) {
            // 识别码模式：匿名接入，身份由 auth 帧的 accessCode 确立
            return true;
        }
        Object loginId;
        try {
            loginId = StpUtil.getLoginIdByToken(token);
        } catch (Exception e) {
            loginId = null;
        }
        if (loginId == null) {
            log.warn("Agent 握手被拒绝: token 无效或已过期, remote={}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, Long.valueOf(String.valueOf(loginId)));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("Agent 握手异常: uri={}", request.getURI(), exception);
        }
    }

    static String resolveParam(String uri, String name) {
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) {
            return null;
        }
        for (String pair : uri.substring(queryStart + 1).split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || !name.equals(pair.substring(0, separator))) {
                continue;
            }
            String value = pair.substring(separator + 1);
            return value.isBlank() ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }
}
