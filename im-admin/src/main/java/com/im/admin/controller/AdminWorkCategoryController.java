package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.admin.service.AdminWorkService;
import com.im.admin.service.WorkAuthGuard;
import com.im.common.api.Result;
import com.im.common.domain.AdminWorkCategoryDTO;
import com.im.common.enums.WorkBoardType;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台的板块分类字典管理（图片 / 音乐 / 小说），一套端点按 {@code {type}} 分发。
 *
 * <p>权限口径与 {@link AdminWorkController} 完全一致：类上只 {@code @SaCheckLogin}，每个方法首行
 * 经 {@link WorkAuthGuard} 动态校验该板块的权限点。分类与作品虽然是两个契约，但它们是<b>同一屏</b>
 * 的两件事——「图片管理」页面里既有列表也有分类页签，所以共用 129 这一个权限点，不细拆。
 *
 * <p>{@code video} 板块的分类仍走既有的 {@code /api/admin/video/category*} 四个端点（URL、DTO、
 * 前端一律不动）：那套端点在 {@code AdminVideoController} 里已用注解固定校验
 * {@code system:video:manage}，没有必要为了统一形态去改一个已上线的 URL。
 *
 * <p>路径用 {@code /category/list} 而不是 {@code GET /category}：后者会与作品详情
 * {@code GET /api/admin/work/{type}/{id}} 的模板重叠，Spring 会把 {@code category} 当成
 * {@code {id}} 去做 Long 转换然后回 400。沿用视频那边的 {@code /category/list} 正好绕开。
 */
@Tag(name = "16-管理后台", description = "作品分类：图片/音乐/小说板块二级分类字典的增删改查")
@RestController
@RequestMapping("/api/admin/work/{type}/category")
@RequiredArgsConstructor
@SaCheckLogin
public class AdminWorkCategoryController {

    private final AdminWorkService adminWorkService;
    private final WorkAuthGuard workAuthGuard;

    @Operation(summary = "分类列表",
            description = "该板块顶部分类栏的全部项（含停用），按 sort 升序；用户端另走 /api/live/{板块}/categories 只拉启用项")
    @GetMapping("/list")
    public Result<List<AdminWorkCategoryDTO>> list(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type) {
        WorkBoardType board = workAuthGuard.check(type);
        return Result.ok(adminWorkService.listCategories(board.getCode()));
    }

    @Operation(summary = "新增分类",
            description = "name 必填（<b>同板块</b>未删除行内不允许同名）；sort 为空则排到最后；新分类默认启用。板块由 URL 决定，不可指定")
    @PostMapping
    public Result<AdminWorkCategoryDTO> create(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                              @Parameter(description = "分类名称") @RequestParam("name") String name,
                                              @Parameter(description = "排序，空则排最后") @RequestParam(value = "sort", required = false) Integer sort) {
        WorkBoardType board = workAuthGuard.check(type);
        return Result.ok(adminWorkService.createCategory(board.getCode(), name, sort, SecurityUtil.getUserId()), "分类已新增");
    }

    @Operation(summary = "修改分类",
            description = "改名 / 调序 / 启停；参数不传表示该项不改。<b>不允许改板块归属</b>，且会校验该分类确实属于 URL 声明的板块")
    @PutMapping("/{id}")
    public Result<AdminWorkCategoryDTO> update(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                               @Parameter(description = "分类 ID") @PathVariable("id") Long id,
                                               @Parameter(description = "新名称，空不改") @RequestParam(value = "name", required = false) String name,
                                               @Parameter(description = "新排序，空不改") @RequestParam(value = "sort", required = false) Integer sort,
                                               @Parameter(description = "状态 1 启用 0 停用，空不改") @RequestParam(value = "status", required = false) Integer status) {
        WorkBoardType board = workAuthGuard.check(type);
        return Result.ok(adminWorkService.updateCategory(board.getCode(), id, name, sort, status, SecurityUtil.getUserId()), "分类已保存");
    }

    @Operation(summary = "删除分类",
            description = "逻辑删除：仅从该板块字典隐身，<b>不动任何已发布作品的 category 文本</b>；幂等")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                               @Parameter(description = "分类 ID") @PathVariable("id") Long id) {
        WorkBoardType board = workAuthGuard.check(type);
        adminWorkService.deleteCategory(board.getCode(), id, SecurityUtil.getUserId());
        return Result.ok(null, "分类已删除");
    }
}
