package com.im.websocket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.config.ImProperties;
import com.im.common.util.SecurityUtil;
import com.im.websocket.dto.WsGroupCallPayload;
import com.im.websocket.dto.vo.CallConfigVO;
import com.im.websocket.service.CallSignalingService;
import com.im.websocket.service.GroupCallSignalingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音视频通话的 HTTP 支撑端点。
 *
 * <p>只有三件事走 REST：拉 ICE 服务器清单、拉通话开关、查本群进行中的群通话。信令本身走已认证的
 * WebSocket 长连接（见 {@code CallSignalingService}、{@code GroupCallSignalingService}
 * 与 {@code WsInboundDispatcher} 的 CALL / GROUPCALL 分支），不在这里重复。
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
    private final GroupCallSignalingService groupCallSignalingService;
    private final ImProperties imProperties;

    @Operation(summary = "获取通话配置",
            description = "需登录。返回通话开关（含群通话）、RTCConfiguration.iceServers；TURN 启用时凭据按当前用户临时签发")
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
        // 群通话开关只在总开关开着时才有意义，上限下发服务端夹过后的值，前端与后端口径一致
        vo.setGroupEnabled(enabled && imProperties.getCall().isGroupEnabled());
        vo.setGroupMaxSlots(groupCallSignalingService.maxSlots());
        return Result.ok(vo);
    }

    /**
     * 查本群此刻有没有人正开着群通话。
     *
     * <p>为什么用 REST 而不走长连接广播：im-websocket 不持有群成员名单（星型约束），
     * 要「开一路通话就告知全群」得先向群组模块要一次全量成员再逐个扇出，
     * 而前端打开群聊时本就调过一次 REST，顺手查一个 Redis 键的成本低得多。
     * 进入群聊之后的增量靠 {@code member-join} / {@code member-leave} / {@code end} 帧维护。
     *
     * <p>直接返回 {@link WsGroupCallPayload} 而不另造一个 VO：这份数据与 {@code roster} 信令帧
     * 完全同形（前端本来就要写一套解析 roster 的逻辑），再开一个字段一模一样的类只多一个漂移点。
     * 花名册条目经 {@code copy()} 过，不含 deviceId。
     *
     * @return 无进行中通话（或功能关闭）时 data 为 {@code null}
     */
    @Operation(summary = "查询本群进行中的群通话",
            description = "需登录且为本群成员。返回花名册与 mediaType，无通话时 data 为空")
    @GetMapping("/group/active")
    public Result<WsGroupCallPayload> groupActive(@RequestParam Long groupId) {
        return Result.ok(groupCallSignalingService.activeCallOf(groupId, SecurityUtil.getUserId()));
    }
}
