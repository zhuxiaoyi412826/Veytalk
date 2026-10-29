package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.live.dto.req.ArticleSaveRequest;
import com.im.live.dto.vo.ArticleVO;
import com.im.live.service.ArticleService;
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

/**
 * 直播页「文章」板块 REST 接口（作者侧）：广场分页、我的分页、详情、新建、编辑、提交审核与删除。
 *
 * <p>单独挂在 {@code /api/article}：与 {@code LiveController} 的 {@code GET /{roomId}} 同理，
 * 若并进别的带路径变量的控制器会把 {@code /page}、{@code /mine} 当成 ID 误吞，独立前缀最省心。
 *
 * <p>正文图片与封面本体的上传复用通用 {@code im-file}（{@code /api/file/upload} 与分片接口，
 * bizType 传 article_img / article_cover），这里只负责文章记录的增删查改与状态流转。
 * 新建/提交限流 10 次/分钟：每次都会落一行待审记录，这条限制挡的是脚本刷审核队列，不是正常用户。
 */
@Tag(name = "10-直播", description = "文章板块：广场/我的分页、详情、新建、编辑、提交审核与删除")
@RestController
@RequestMapping("/api/article")
@RequiredArgsConstructor
@SaCheckLogin
public class ArticleController {

    private final ArticleService articleService;

    @Operation(summary = "文章广场分页", description = "仅已发布（status=2），按创建时间倒序；列表不含正文；分页上限 100")
    @GetMapping("/page")
    public Result<Page<ArticleVO>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "12") long size) {
        return Result.ok(articleService.pagePublic(current, Math.min(size, 100)));
    }

    @Operation(summary = "我的文章", description = "当前用户所写，含草稿/待审/驳回/已发布/已下架全部状态；列表不含正文")
    @GetMapping("/mine")
    public Result<Page<ArticleVO>> mine(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "12") long size) {
        return Result.ok(articleService.pageMine(current, Math.min(size, 100)));
    }

    @Operation(summary = "文章详情", description = "含正文；已发布对任何人可见，未发布仅本人与管理员可见。已发布时浏览量 +1")
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@PathVariable("id") Long id) {
        return Result.ok(articleService.detail(id));
    }

    @Operation(summary = "新建文章",
            description = "action=draft 存草稿 / submit 提交审核；正文图片与封面先用对应 bizType 上传拿 fileId 再写进 Markdown")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "article.create")
    @PostMapping
    public Result<ArticleVO> create(@RequestBody @Valid ArticleSaveRequest request) {
        return Result.ok(articleService.create(request));
    }

    @Operation(summary = "编辑文章", description = "仅本人且草稿/驳回态可编辑；action 决定改完是维持草稿还是重新送审")
    @PutMapping("/{id}")
    public Result<ArticleVO> update(@PathVariable("id") Long id, @RequestBody @Valid ArticleSaveRequest request) {
        return Result.ok(articleService.update(id, request));
    }

    @Operation(summary = "提交审核", description = "把本人草稿/驳回文章置为待审（1）；非法状态或越权报错")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "article.submit")
    @PostMapping("/{id}/submit")
    public Result<ArticleVO> submit(@PathVariable("id") Long id) {
        return Result.ok(articleService.submit(id));
    }

    @Operation(summary = "删除文章", description = "仅作者本人可删（逻辑删除）；记录不存在幂等返回成功")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        articleService.delete(id);
        return Result.ok();
    }
}
