package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.enums.WorkBoardType;
import com.im.common.security.ratelimit.RateLimit;
import com.im.live.dto.req.LiveMusicCreateRequest;
import com.im.live.dto.vo.WorkVO;
import com.im.live.service.LiveMusicService;
import com.im.live.service.LiveVideoCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 直播页「音乐」板块 REST 接口：音频信息流的分页、发布、编辑、删除与播放计数。
 *
 * <p>挂在 {@code /api/live/music}：与 {@code LiveController} 的 {@code GET /{roomId}} 让路，
 * 理由同 {@link LiveImageController}。
 *
 * <p>音频与封面本体走通用 {@code im-file}（bizType 传 {@code music} / {@code music_cover}），
 * 本接口只落信息流记录。前端拿到的 {@code fileUrl} 是受控地址，经 {@code mediaUrl} 取 blob 后
 * 交给 {@code <audio>}——不直挂 {@code src}，因为下载接口要求带登录头。
 */
@Tag(name = "10-直播", description = "音乐板块：音频信息流分页、发布、编辑、删除与播放计数")
@RestController
@RequestMapping("/api/live/music")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveMusicController {

    /** 本控制器固定的板块码，同时用于查该板块的分类字典 */
    private static final String BOARD = WorkBoardType.MUSIC.getCode();

    private final LiveMusicService musicService;
    private final LiveVideoCategoryService categoryService;

    @Operation(summary = "音乐分类列表",
            description = "音乐板块顶部分类栏与发布分类下拉的数据源：仅启用项、按 sort 升序；板块由服务端写死，不接前端传值")
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.ok(categoryService.enabledNames(BOARD));
    }

    @Operation(summary = "音乐分页",
            description = "仅正常状态作品；category 二级分类精确过滤、keyword 标题模糊搜索、sort=hot 按播放次数否则按时间、mine=true 只看本人；分页上限 100")
    @GetMapping("/page")
    public Result<Page<WorkVO>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "15") long size,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String sort,
                                     @RequestParam(defaultValue = "false") boolean mine) {
        return Result.ok(musicService.page(current, Math.min(size, 100), category, keyword, sort, mine));
    }

    @Operation(summary = "发布音乐",
            description = "先用 bizType=music 上传音频（可选 bizType=music_cover 传封面）拿到 fileId，再调本接口建记录；受控地址由服务端拼")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.music.create")
    @PostMapping
    public Result<WorkVO> create(@RequestBody @Valid LiveMusicCreateRequest request) {
        return Result.ok(musicService.publish(request));
    }

    @Operation(summary = "编辑音乐",
            description = "仅作者本人；整表单替换，coverFileId 传空即清空封面")
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.music.update")
    @PutMapping("/{id}")
    public Result<WorkVO> update(@PathVariable("id") Long id,
                                 @RequestBody @Valid LiveMusicCreateRequest request) {
        return Result.ok(musicService.edit(id, request));
    }

    @Operation(summary = "删除音乐", description = "仅作者本人可删（逻辑删除）；记录不存在幂等返回成功")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        musicService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "播放上报", description = "开始播放时 +1，播放计数非强一致")
    @PostMapping("/{id}/play")
    public Result<Void> play(@PathVariable("id") Long id) {
        musicService.increasePlayCount(id);
        return Result.ok();
    }
}
