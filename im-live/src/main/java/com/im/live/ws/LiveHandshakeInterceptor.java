package com.im.live.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.im.live.config.LiveProperties;
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
 * 弹幕通道握手拦截器：校验登录态与房间号，不校验「房间是否活着」。
 *
 * <p>登录态必须校验：弹幕要带上发言人的昵称/头像/是否主播，匿名连接无法归属，
 * 也让限流失去维度（只能按 IP，一个 NAT 后全场共享配额）。与远控控制端握手同源——
 * WebSocket 升级请求不经过 Sa-Token 的 /api/** 拦截器，也带不了自定义头，
 * 所以前端把 satoken 挂在握手 URL 上，这里用 {@code getLoginIdByToken} 验一次。
 *
 * <p>「房间是否还在直播」刻意留到 {@code afterConnectionEstablished} 再判：
 * 握手阶段查一次库、连接建立后再查一次是重复开销，而关播是低频事件，
 * 让 handler 用一次 {@code findLiving} 兜住即可，握手只做「你是谁」不做「房间在不在」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "im.live.userId";
    public static final String ATTR_ROOM_ID = "im.live.roomId";

    private final LiveProperties properties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!properties.isEnabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        String uri = request.getURI().toString();
        Long roomId = parseRoomId(resolveParam(uri, "roomId"));
        if (roomId == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        Long userId = currentUserId(uri);
        if (userId == null) {
            log.warn("弹幕握手被拒绝: satoken 登录态校验未通过, roomId={}, remote={}", roomId, request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_ROOM_ID, roomId);
        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("弹幕握手异常: uri={}", request.getURI(), exception);
        }
    }

    private Long currentUserId(String uri) {
        String token = resolveParam(uri, "satoken");
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            return loginId == null ? null : Long.valueOf(String.valueOf(loginId));
        } catch (Exception e) {
            log.warn("弹幕握手 satoken 校验抛异常: {}", e.toString());
            return null;
        }
    }

    private Long parseRoomId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从握手 URL 的 query 里取参数，与远控 AgentHandshakeInterceptor 同一套解析规则 */
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
