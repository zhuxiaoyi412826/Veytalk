package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.Result;
import com.im.common.enums.WorkBoardType;
import com.im.common.security.ratelimit.RateLimit;
import com.im.live.dto.req.LiveImageCreateRequest;
import com.im.live.dto.vo.WorkVO;
import com.im.live.service.LiveImageService;
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
 * 直播页「图片」板块 REST 接口：图集信息流的分页、发布、编辑、删除与打开计数。
 *
 * <p>挂在 {@code /api/live/image} 而不是并入 {@code LiveController}：后者的
 * {@code GET /{roomId}} 会把 {@code image} 当成 roomId 路径变量误吞（与视频板块同一理由）。
 *
 * <p>图片本体的上传复用通用 {@code im-file}（{@code /api/file/upload} 与分片接口，bizType 传
 * {@code image_work}），这里只负责建/查/改/删信息流记录。板块标识在服务端写死为 {@code image}，
 * <b>不接前端传值</b>：否则一条 {@code type=novel} 就能把图集发到小说栏去。
 *
 * <p>限流：发布 10 次/分钟、编辑 30 次/分钟（{@code dimension = USER}），与视频板块同口径——
 * 挡的是脚本刷记录，不是正常用户。
 */
@Tag(name = "10-直播", description = "图片板块：图集信息流分页、发布、编辑、删除与打开计数")
@RestController
@RequestMapping("/api/live/image")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveImageController {

    /** 本控制器固定的板块码，同时用于查该板块的分类字典 */
    private static final String BOARD = WorkBoardType.IMAGE.getCode();

    private final LiveImageService imageService;
    private final LiveVideoCategoryService categoryService;

    @Operation(summary = "图片分类列表",
            description = "图片板块顶部分类栏与发布分类下拉的数据源：仅启用项、按 sort 升序；板块由服务端写死，不接前端传值")
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.ok(categoryService.enabledNames(BOARD));
    }

    @Operation(summary = "图集分页",
            description = "仅正常状态作品；category 二级分类精确过滤、keyword 标题模糊搜索、sort=hot 按打开次数否则按时间、mine=true 只看本人；分页上限 100")
    @GetMapping("/page")
    public Result<Page<WorkVO>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "15") long size,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String sort,
                                     @RequestParam(defaultValue = "false") boolean mine) {
        return Result.ok(imageService.page(current, Math.min(size, 100), category, keyword, sort, mine));
    }

    @Operation(summary = "发布图集",
            description = "先用 bizType=image_work 逐张上传拿到 fileId（1~9 张），再调本接口建记录；首图自动作封面，受控地址由服务端拼")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.image.create")
    @PostMapping
    public Result<WorkVO> create(@RequestBody @Valid LiveImageCreateRequest request) {
        return Result.ok(imageService.publish(request));
    }

    @Operation(summary = "编辑图集",
            description = "仅作者本人；整表单替换（imageIds 仍是最终要展示的那 1~9 张，顺序即展示顺序）")
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.image.update")
    @PutMapping("/{id}")
    public Result<WorkVO> update(@PathVariable("id") Long id,
                                 @RequestBody @Valid LiveImageCreateRequest request) {
        return Result.ok(imageService.edit(id, request));
    }

    @Operation(summary = "删除图集", description = "仅作者本人可删（逻辑删除）；记录不存在幂等返回成功")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        imageService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "打开上报", description = "点开图集弹层时 +1，计数非强一致")
    @PostMapping("/{id}/play")
    public Result<Void> play(@PathVariable("id") Long id) {
        imageService.increasePlayCount(id);
        return Result.ok();
    }
}
