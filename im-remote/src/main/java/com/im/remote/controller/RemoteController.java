package com.im.remote.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.remote.dto.req.RemoteCodeInviteRequest;
import com.im.remote.dto.req.RemoteInviteRequest;
import com.im.remote.entity.RemoteAuditLog;
import com.im.remote.entity.RemoteDevice;
import com.im.remote.entity.RemoteSession;
import com.im.remote.manager.AgentRegistry;
import com.im.remote.service.RemoteDeviceService;
import com.im.remote.service.RemoteRelayService;
import com.im.remote.service.RemoteSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程控制 REST 接口：设备、邀请、会话收尾与审计。
 *
 * <p>这里只有「控制面」——数据面（屏幕帧/输入事件/文件块）全部走两条专用
 * WebSocket 端点，REST 一个字节都不碰。控制面刻意做得很薄：
 * 发起邀请后前端轮询会话详情（60 秒内、2 秒一次），拿到 ticket 即建 WS，
 * 相比再开一条推送通道，轮询在授权这种一次性、秒级容忍度的场景里更简单可靠。
 *
 * <p>邀请接口限流 10 次/分钟：每次邀请都会在被控端弹出授权窗口，
 * 高频邀请等于对被控方做 UI 拒绝服务，这条限制保护的是人不是服务器。
 */
@Tag(name = "09-远程控制", description = "被控设备管理、远程会话邀请与审计")
@RestController
@RequestMapping("/api/remote")
@RequiredArgsConstructor
@SaCheckLogin
public class RemoteController {

    private final RemoteDeviceService deviceService;
    private final RemoteSessionService sessionService;
    private final AgentRegistry agentRegistry;
    private final RemoteRelayService relayService;

    @Operation(summary = "我的设备列表",
            description = "含在线状态（0 离线 / 1 空闲 / 2 忙 / 3 拒绝接入）与识别码；status 以连接注册表为准修正库值")
    @GetMapping("/devices")
    public Result<List<Map<String, Object>>> devices() {
        Long userId = SecurityUtil.getUserId();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RemoteDevice device : deviceService.listByUser(userId)) {
            boolean online = agentRegistry.get(userId, device.getDeviceId()) != null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", device.getDeviceId());
            item.put("deviceName", device.getDeviceName());
            item.put("os", device.getOs());
            item.put("accessCode", device.getAccessCode());
            item.put("status", online ? device.getStatus() : RemoteDevice.STATUS_OFFLINE);
            item.put("lastOnlineTime", device.getLastOnlineTime());
            result.add(item);
        }
        return Result.ok(result);
    }

    @Operation(summary = "发起远程邀请",
            description = "向被控端 Agent 推送授权弹窗；返回 sessionId 供轮询 /session/{id} 获取 ticket")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "remote.invite")
    @PostMapping("/session/invite")
    public Result<Map<String, Object>> invite(@RequestBody @Valid RemoteInviteRequest request) {
        return Result.ok(sessionService.invite(request.getDeviceId(), request.getPermission()));
    }

    @Operation(summary = "凭识别码发起远程邀请",
            description = "ToDesk 式跨账号接入：控制方必须已登录，被控端只需在 Agent 设置识别码（无需账号）；"
                    + "错误文案合并「不在线/码错」防探测，限流比设备邀请更严")
    @RateLimit(count = 5, seconds = 60, dimension = RateLimit.Dimension.USER, key = "remote.invite-code")
    @PostMapping("/session/invite-by-code")
    public Result<Map<String, Object>> inviteByCode(@RequestBody @Valid RemoteCodeInviteRequest request) {
        return Result.ok(sessionService.inviteByCode(request.getCode(), request.getPermission()));
    }

    @Operation(summary = "会话详情（轮询授权进度）",
            description = "inviting 表示等待对方面授权；active 时返回一次性 ticket 与会话 aesKey")
    @GetMapping("/session/{id}")
    public Result<Map<String, Object>> sessionDetail(@PathVariable("id") Long id) {
        return Result.ok(sessionService.detail(id));
    }

    @Operation(summary = "结束远程会话")
    @PostMapping("/session/{id}/end")
    public Result<Void> endSession(@PathVariable("id") Long id) {
        sessionService.endByInviter(id);
        return Result.ok();
    }

    @Operation(summary = "我的远程会话分页", description = "作为控制方或被控方的全部会话历史")
    @GetMapping("/session/page")
    public Result<Page<RemoteSession>> sessionPage(@RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "20") long size) {
        return Result.ok(sessionService.sessionPage(current, size));
    }

    @Operation(summary = "会话审计分页", description = "会话内的操作流水（文件删除/结束进程/cmd/电源/输入拦截等）")
    @GetMapping("/session/{id}/audit")
    public Result<Page<RemoteAuditLog>> auditPage(@PathVariable("id") Long id,
                                                  @RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "50") long size) {
        return Result.ok(sessionService.auditPage(id, current, Math.min(size, 100)));
    }
}
