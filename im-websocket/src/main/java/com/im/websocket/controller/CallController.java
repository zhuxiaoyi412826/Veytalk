package com.im.websocket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.config.ImProperties;
import com.im.common.util.SecurityUtil;
import com.im.websocket.dto.vo.CallConfigVO;
import com.im.websocket.service.CallSignalingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音视频通话的 HTTP 支撑端点。
 *
 * <p>只有一件事走 REST：拉 ICE 服务器清单。信令本身走已认证的 WebSocket 长连接（见
 * {@code CallSignalingService} 与 {@code WsInboundDispatcher} 的 CALL 分支），不在这里重复。
 *
 * <p>ICE 清单按登录用户现取而不硬编码在前端，有两个原因：TURN 的临时凭据必须以服务端身份、
 * 按 userId 签发（前端拿不到那个 HMAC 密钥）；且 {@code im.call.enabled} 一关，前端就不会
 * 再加载任何 WebRTC 代码，做到真正的总开关。
 */
@Tag(name = "12-音视频通话", description = "通话 ICE 配置下发")
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
@SaCheckLogin
public class CallController {

    private final CallSignalingService callSignalingService;
    private final ImProperties imProperties;

    @Operation(summary = "获取通话配置",
            description = "需登录。返回通话开关与 RTCConfiguration.iceServers；TURN 启用时凭据按当前用户临时签发")
    @GetMapping("/config")
    public Result<CallConfigVO> config() {
        CallConfigVO vo = new CallConfigVO();
        boolean enabled = imProperties.getCall().isEnabled();
        vo.setEnabled(enabled);
        // 关闭时不下发服务器清单，免得前端拿着也没用、还多暴露一处 TURN 地址
        List<java.util.Map<String, Object>> servers = enabled
                ? callSignalingService.buildIceServers(SecurityUtil.getUserId())
                : List.of();
        vo.setIceServers(servers);
        return Result.ok(vo);
    }
}
