package com.im.websocket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.websocket.dto.vo.WsTicketVO;
import com.im.websocket.service.WsTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebSocket 接入端点。
 *
 * <p>只有换票据这一个接口走 HTTP，握手本身不经过 Spring MVC——它由
 * {@code WebSocketConfig} 注册在 {@code /ws} 上，路径不在 {@code /api/**} 之下，
 * 因此 Sa-Token 拦截器管不到它，鉴权全部由 {@code WsHandshakeInterceptor} 校验票据完成。
 *
 * <p>类上的 {@code @SaCheckLogin} 与 {@code SaTokenConfigure} 里的路由级校验是重复的，
 * 保留它是为了与其他业务 controller 保持一致：注解写在类上，
 * 读代码的人一眼就知道这个端点要登录，不必去翻拦截器的白名单。
 */
@Tag(name = "07-实时推送", description = "WebSocket 连接票据")
@RestController
@RequestMapping("/api/ws")
@RequiredArgsConstructor
@SaCheckLogin
public class WsTicketController {

    private final WsTicketService ticketService;

    /**
     * 换取连接票据。
     *
     * <p>前端在建立连接前调用，拿到 ticket 后拼成
     * {@code ws://host:8080/ws?ticket=xxx} 发起握手。票据默认 60 秒有效
     * （{@code im.jwt.ticket-ttl-seconds}），过期后重连必须重新申请——
     * 前端的重连逻辑里要把「取票据」放在「建连接」之前，不能缓存第一次的票据反复用。
     */
    @Operation(summary = "获取 WebSocket 连接票据",
            description = "需登录。票据默认 60 秒内有效，握手时作为 ticket 查询参数携带；"
                    + "断线重连必须重新申请，不能复用旧票据")
    @PostMapping("/ticket")
    public Result<WsTicketVO> ticket(
            @Parameter(description = "设备标识：web / pc / android / ios / mini，未知值按 web 处理；"
                    + "缺省时读 X-Device-Id 请求头")
            @RequestParam(required = false) String deviceId) {
        // 显式参数优先，其次回落到 SecurityUtil.getDevice()，让「查询参数」和「请求头」两种传法都能工作
        String device = TextUtil.isBlank(deviceId) ? SecurityUtil.getDevice() : deviceId;
        return Result.ok(ticketService.issue(SecurityUtil.getUserId(), device));
    }
}
