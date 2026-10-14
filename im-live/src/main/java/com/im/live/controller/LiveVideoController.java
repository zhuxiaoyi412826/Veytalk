package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.live.dto.req.LiveVideoCreateRequest;
import com.im.live.dto.vo.LiveVideoVO;
import com.im.live.service.LiveVideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 直播页「视频」板块 REST 接口：短视频信息流的分页、发布、删除与播放计数。
 *
 * <p>单独挂在 {@code /api/live/video} 而不是并入 {@code LiveController}：后者的
 * {@code GET /{roomId}} 会把 {@code /video} 当成 roomId 路径变量误吞，分路由最省心。
 *
 * <p>视频与封面本体的上传复用通用 {@code im-file}（{@code /api/file/upload} 与分片接口，
 * bizType 传 video / video_cover），这里只负责建/查/删信息流记录。发布限流 10 次/分钟：
 * 每次发布会落一行信息流记录，这条限制挡的是脚本刷记录，不是正常用户。
 */
@Tag(name = "10-直播", description = "视频板块：短视频信息流分页、发布、删除与播放计数")
@RestController
@RequestMapping("/api/live/video")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveVideoController {

    private final LiveVideoService videoService;

    @Operation(summary = "视频分页", description = "仅正常状态视频，按发布时间倒序；分页上限 100")
    @GetMapping("/page")
    public Result<Page<LiveVideoVO>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "24") long size) {
        return Result.ok(videoService.page(current, Math.min(size, 100)));
    }

    @Operation(summary = "发布视频",
            description = "先用 bizType=video/video_cover 上传拿到 fileId，再调本接口建信息流记录；受控地址由服务端拼")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.video.create")
    @PostMapping
    public Result<LiveVideoVO> create(@RequestBody @Valid LiveVideoCreateRequest request) {
        return Result.ok(videoService.create(request));
    }

    @Operation(summary = "删除视频", description = "仅上传者本人可删（逻辑删除）；记录不存在幂等返回成功")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        videoService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "播放上报", description = "点开视频时 +1，播放计数非强一致")
    @PostMapping("/{id}/play")
    public Result<Void> play(@PathVariable("id") Long id) {
        videoService.increasePlayCount(id);
        return Result.ok();
    }
}
