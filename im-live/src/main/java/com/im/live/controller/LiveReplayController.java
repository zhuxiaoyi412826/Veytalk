package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.live.dto.req.LiveReplayRequest;
import com.im.live.dto.vo.LiveReplayVO;
import com.im.live.dto.vo.LiveRoomVO;
import com.im.live.service.LiveReplayService;
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

import java.util.List;

/**
 * 直播回放的 REST 入口：主播上报、观众取回放地址、大厅列可回看场次。
 *
 * <p>清单本身不走这里——它是推流端直接 PUT 到媒体服务器的（控制面/媒体面分离，
 * Java 一个媒体字节都不碰）。这三个接口只搬运「有回放」这个事实与它的展示数据。
 *
 * <p>{@code GET /api/live/replays} 不歧义：Spring 匹配时对字面量路径优先于
 * {@code /{roomId}} 模板，不会被当成一个 roomId 为 "replays" 的详情请求。
 */
@Tag(name = "10-直播", description = "直播回放：主播上报、观众取回放地址、可回看场次")
@RestController
@RequestMapping("/api/live")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveReplayController {

    private final LiveReplayService replayService;
    /**
     * 列表给的是房间视图（含标题/封面/主播），所以走 LiveRoomService。
     * 依赖方向是单向的（LiveRoomServiceImpl → LiveReplayService），不构成循环。
     */
    private final LiveRoomService roomService;

    @Operation(summary = "上报回放",
            description = "主播关播后由推流端上报：分片数/时长/字节数来自上传台账，服务端夹取后写入入口标记（只存 Redis）。"
                    + "可重复上报，后写覆盖前写")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.replay.report")
    @PostMapping("/{roomId}/replay")
    public Result<Void> report(@PathVariable("roomId") Long roomId,
                               @RequestBody @Valid LiveReplayRequest request) {
        replayService.report(roomId, SecurityUtil.getUserId(), request);
        return Result.ok();
    }

    @Operation(summary = "取回放",
            description = "返回现签的 VOD 清单地址与时长/体积；没有回放入口时返回 null（不报错），前端据此提示「回放已过期」")
    @GetMapping("/{roomId}/replay")
    public Result<LiveReplayVO> replay(@PathVariable("roomId") Long roomId) {
        return Result.ok(replayService.get(roomId));
    }

    @Operation(summary = "可回看场次",
            description = "按关播时间倒序列出「有回放入口」的已结束房间。不随带地址：签名有有效期，"
                    + "进了房间再调取回放接口现签。limit 服务端夹到 [1,20]")
    @GetMapping("/replays")
    public Result<List<LiveRoomVO>> replays(@RequestParam(value = "anchorId", required = false) Long anchorId,
                                            @RequestParam(value = "limit", required = false,
                                                    defaultValue = "12") Integer limit) {
        return Result.ok(roomService.recentReplays(anchorId, limit == null ? 12 : limit));
    }
}
