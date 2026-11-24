package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.enums.WorkBoardType;
import com.im.common.security.ratelimit.RateLimit;
import com.im.live.dto.req.LiveNovelCreateRequest;
import com.im.live.dto.vo.WorkVO;
import com.im.live.service.LiveNovelService;
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
 * 直播页「小说」板块 REST 接口：图文信息流的分页、阅读详情、发布、编辑与删除。
 *
 * <p>挂在 {@code /api/live/novel}，理由同 {@link LiveImageController}。
 *
 * <p>本板块是三个里唯一多一个 {@code GET /{id}} 的：{@code im_live_novel.content} 是 LONGTEXT，
 * 分页查询必须把它排除在外（见 {@link LiveNovelService} 类注释），所以点开某一篇时要单独取一次
 * 正文。<b>不要为了少一个接口把正文塞回列表</b>——那会让一屏 15 篇长文变成接口的内存黑洞。
 */
@Tag(name = "10-直播", description = "小说板块：Markdown 作品信息流分页、阅读详情、发布、编辑与删除")
@RestController
@RequestMapping("/api/live/novel")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveNovelController {

    /** 本控制器固定的板块码，同时用于查该板块的分类字典 */
    private static final String BOARD = WorkBoardType.NOVEL.getCode();

    private final LiveNovelService novelService;
    private final LiveVideoCategoryService categoryService;

    @Operation(summary = "小说分类列表",
            description = "小说板块顶部分类栏与发布分类下拉的数据源：仅启用项、按 sort 升序；板块由服务端写死，不接前端传值")
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.ok(categoryService.enabledNames(BOARD));
    }

    @Operation(summary = "小说分页",
            description = "仅正常状态作品，<b>响应不含正文</b>；category 精确过滤、keyword 只搜标题（不搜正文，LONGTEXT 上的 LIKE 无索引可用）、sort=hot 按阅读次数否则按时间、mine=true 只看本人")
    @GetMapping("/page")
    public Result<Page<WorkVO>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "15") long size,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String sort,
                                     @RequestParam(defaultValue = "false") boolean mine) {
        return Result.ok(novelService.page(current, Math.min(size, 100), category, keyword, sort, mine));
    }

    @Operation(summary = "小说阅读详情",
            description = "点开弹层时取正文（Markdown 原文，前端交 MarkdownView 渲染）并顺手把阅读数 +1；已下架与不存在同样回 NOT_FOUND")
    @GetMapping("/{id}")
    public Result<WorkVO> detail(@PathVariable("id") Long id) {
        return Result.ok(novelService.detail(id));
    }

    @Operation(summary = "发布小说",
            description = "正文 Markdown 直接随请求提交（不走 im-file），上限 20 万字符、超限拒绝并提示分篇；字数与字节数服务端现算，摘要留空则从正文截取")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.novel.create")
    @PostMapping
    public Result<WorkVO> create(@RequestBody @Valid LiveNovelCreateRequest request) {
        return Result.ok(novelService.publish(request));
    }

    @Operation(summary = "编辑小说",
            description = "仅作者本人；整表单替换（content 是最终正文，漏传即报错而不是清空）")
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.novel.update")
    @PutMapping("/{id}")
    public Result<WorkVO> update(@PathVariable("id") Long id,
                                 @RequestBody @Valid LiveNovelCreateRequest request) {
        return Result.ok(novelService.edit(id, request));
    }

    @Operation(summary = "删除小说", description = "仅作者本人可删（逻辑删除）；记录不存在幂等返回成功")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        novelService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "阅读上报", description = "列表卡片曝光后仅点开不展开正文等场景补计数；详情页走 {@code GET /{id}} 时已自增，别重复调")
    @PostMapping("/{id}/play")
    public Result<Void> play(@PathVariable("id") Long id) {
        novelService.increasePlayCount(id);
        return Result.ok();
    }
}
