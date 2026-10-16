package com.im.websocket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.config.ImProperties;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.UserQuerySpi;
import com.im.websocket.dto.vo.LinkMicConfigVO;
import com.im.websocket.dto.vo.LinkMicGuestVO;
import com.im.websocket.service.LiveLinkSignalingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 直播连麦的 HTTP 支撑端点。
 *
 * <p>与通话一致，只有「前端需要提前拿到的东西」走 REST：连麦开关与 ICE 清单、当前在麦嘉宾列表。
 * 连麦协商信令本身走已认证的 {@code /ws} 长连接（见 {@code LiveLinkSignalingService} 与
 * {@code WsInboundDispatcher} 的 LINKMIC 分支），不在这里重复。
 *
 * <p>本控制器落在 im-websocket 而非方案字面的 im-live {@code LiveController}：连麦信令服务与在麦态都归
 * im-websocket（星型架构下 im-live 与 im-websocket 互不依赖），把读取这些态的 REST 放在一起才不必反向依赖。
 */
@Tag(name = "14-直播连麦", description = "连麦 ICE 配置下发与在麦列表查询")
@RestController
@RequestMapping("/api/live")
@RequiredArgsConstructor
@SaCheckLogin
public class LinkMicController {

    private final LiveLinkSignalingService linkSignalingService;
    private final UserQuerySpi userQuerySpi;
    private final ImProperties imProperties;

    @Operation(summary = "获取连麦配置",
            description = "需登录。返回连麦开关、麦位上限与 RTCConfiguration.iceServers；ICE 复用通话下发")
    @GetMapping("/{roomId}/linkmic/config")
    public Result<LinkMicConfigVO> config(@PathVariable("roomId") Long roomId) {
        LinkMicConfigVO vo = new LinkMicConfigVO();
        boolean enabled = imProperties.getLinkmic().isEnabled();
        vo.setEnabled(enabled);
        vo.setMaxSlots(imProperties.getLinkmic().getMaxSlots());
        // 关闭时不下发服务器清单，免得前端拿着也没用、还多暴露一处 TURN 地址
        vo.setIceServers(enabled
                ? linkSignalingService.buildLinkIceServers(com.im.common.util.SecurityUtil.getUserId())
                : List.of());
        return Result.ok(vo);
    }

    @Operation(summary = "查询在麦嘉宾", description = "需登录。返回该房间当前在麦的嘉宾列表（不含主播）")
    @GetMapping("/{roomId}/linkmic")
    public Result<List<LinkMicGuestVO>> onMic(@PathVariable("roomId") Long roomId) {
        Set<Long> ids = linkSignalingService.onMicUserIds(roomId);
        if (ids.isEmpty()) {
            return Result.ok(List.of());
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(ids);
        List<LinkMicGuestVO> guests = new ArrayList<>(ids.size());
        for (Long id : ids) {
            UserBriefDTO brief = briefs.get(id);
            String name = brief == null ? "嘉宾" : (brief.getNickname() != null && !brief.getNickname().isBlank()
                    ? brief.getNickname() : brief.getUsername());
            String avatar = brief == null ? null : brief.getAvatar();
            guests.add(new LinkMicGuestVO(id, name, avatar));
        }
        return Result.ok(guests);
    }
}
