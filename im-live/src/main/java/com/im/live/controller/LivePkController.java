package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.live.dto.req.LivePkStartRequest;
import com.im.live.dto.vo.LivePkVO;
import com.im.live.service.LivePkService;
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

/**
 * 连麦 PK 的 REST 入口：发起 / 查询 / 投票 / 提前结束。
 *
 * <p>进度帧不走这里——那是 {@code /ws/live} 的事（服务端每秒一拍扇出给两个房间）。
 * REST 只承担「人要主动点的那几下」，所以每个端点都限流：投票尤其，否则脚本可以按秒刷人头。
 */
@Tag(name = "10-直播", description = "连麦 PK：发起、查询、投票")
@RestController
@RequestMapping("/api/live/pk")
@RequiredArgsConstructor
@SaCheckLogin
public class LivePkController {

    private final LivePkService pkService;

    @Operation(summary = "发起 PK",
            description = "对方必须也在直播中（通常是当前连麦的嘉宾）；时长不传用默认值，超上限按上限取")
    @RateLimit(count = 5, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.pk.start")
    @PostMapping("/start")
    public Result<LivePkVO> start(@RequestBody @Valid LivePkStartRequest request) {
        return Result.ok(pkService.start(SecurityUtil.getUserId(),
                request.getTargetUserId(), request.getDurationSeconds()));
    }

    @Operation(summary = "房间当前 PK",
            description = "进行中的对局快照，或刚结束时定格的胜负结果；没有则返回 null。进房补帧走 WS，这个接口是给刷新页面用的")
    @GetMapping("/current")
    public Result<LivePkVO> current(@RequestParam("roomId") Long roomId) {
        return Result.ok(pkService.current(roomId));
    }

    @Operation(summary = "投票站队",
            description = "side 取 A 或 B；一人一票，改票即覆盖，票数只按人头算")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.pk.vote")
    @PostMapping("/{pkId}/vote")
    public Result<LivePkVO> vote(@PathVariable("pkId") String pkId, @RequestParam("side") String side) {
        return Result.ok(pkService.vote(SecurityUtil.getUserId(), pkId, side));
    }

    @Operation(summary = "提前结束", description = "只有对阵双方的主播能喊停；按当时热度定胜负，原因记为 manual")
    @PostMapping("/{pkId}/stop")
    public Result<LivePkVO> stop(@PathVariable("pkId") String pkId) {
        return Result.ok(pkService.stop(SecurityUtil.getUserId(), pkId));
    }
}
