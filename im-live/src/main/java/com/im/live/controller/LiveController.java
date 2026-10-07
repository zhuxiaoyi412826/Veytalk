package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.live.dto.req.LiveStartRequest;
import com.im.live.dto.vo.LiveRoomVO;
import com.im.live.service.LiveRoomService;
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

import java.util.Map;

/**
 * 直播控制面 REST 接口：开播、关播、心跳、房间列表与详情。
 *
 * <p>与远控一样，这里只有「控制面」——媒体字节（HLS 分片）由推流端 ffmpeg 直接 PUT 到
 * Nginx、观众从 Nginx 拉流，弹幕走独立的 {@code /ws/live} 通道，REST 一个媒体字节都不碰。
 *
 * <p>开播限流 5 次/分钟：每次开播都会新建一行房间记录并生成推流目录，
 * 高频开播等于往库里灌垃圾房间，这条限制挡的是脚本刷房间，不是正常主播。
 */
@Tag(name = "10-直播", description = "直播房间开播/关播/心跳、房间列表与详情")
@RestController
@RequestMapping("/api/live")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveController {

    private final LiveRoomService roomService;

    @Operation(summary = "开播",
            description = "新建一场直播并下发推流信息：roomId / streamKey（只此一次）/ pushUrl / playUrl / heartbeatSeconds / danmakuWs")
    @RateLimit(count = 5, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.start")
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody @Valid LiveStartRequest request) {
        return Result.ok(roomService.start(request));
    }

    @Operation(summary = "关播", description = "主播主动结束直播；房间已结束则幂等返回成功")
    @PostMapping("/{roomId}/stop")
    public Result<Void> stop(@PathVariable("roomId") Long roomId) {
        roomService.stop(roomId, "stop");
        return Result.ok();
    }

    @Operation(summary = "撤销未推流的直播",
            description = "建房成功但推流起不来时调用：从未收到过推流端心跳的房间直接删除，不留空场 ENDED 房；"
                    + "已推过流的降级为普通关播。房间不存在时幂等返回成功")
    @PostMapping("/{roomId}/abort")
    public Result<Void> abort(@PathVariable("roomId") Long roomId) {
        roomService.abort(roomId);
        return Result.ok();
    }

    @Operation(summary = "推流心跳",
            description = "推流端按 heartbeatSeconds 周期调用续期；返回 false 表示房间已不存在或已结束，推流端必须停止 ffmpeg")
    @PostMapping("/{roomId}/heartbeat")
    public Result<Boolean> heartbeat(@PathVariable("roomId") Long roomId) {
        return Result.ok(roomService.heartbeat(roomId));
    }

    @Operation(summary = "房间详情", description = "含签名播放地址与弹幕端点（仅直播中），进房时现取现用，不要长期缓存")
    @GetMapping("/{roomId}")
    public Result<LiveRoomVO> detail(@PathVariable("roomId") Long roomId) {
        return Result.ok(roomService.detail(roomId));
    }

    @Operation(summary = "房间分页", description = "直播中的房间恒排最前，其次按开播时间倒序；status 留空为全部（1 直播中 2 已结束 3 已封禁）；已结束且关播超过阈值（默认 60 分钟）的房间不再返回")
    @GetMapping("/page")
    public Result<Page<LiveRoomVO>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) Integer status) {
        return Result.ok(roomService.page(current, Math.min(size, 100), status));
    }

    @Operation(summary = "我的直播", description = "我最近的一场直播（直播中优先），用于「我的直播」面板恢复现场；一场都没开过返回 null")
    @GetMapping("/mine")
    public Result<LiveRoomVO> mine() {
        return Result.ok(roomService.mine());
    }
}
