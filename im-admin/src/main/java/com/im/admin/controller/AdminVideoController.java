package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminVideoService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminVideoCategoryDTO;
import com.im.common.domain.AdminVideoDTO;
import com.im.common.domain.AdminVideoQueryDTO;
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
 * 后台的视频管理（三期）。
 *
 * <p>下架与恢复拆成两个 POST 而不是一个带 boolean 的 PUT，沿用
 * {@code AdminUserController} 的 disable/enable 分路口径：URL 自己说明在做什么，
 * 审计日志里不出现要靠参数值才读得懂的行。
 *
 * <p><b>删除是逻辑删除且不可自助恢复</b>（一期没有回收站页），所以前端把删除做成
 * 需要二次确认的高危动作；这里不做「删了还能查」的接口——{@code deleted=1} 的行
 * MyBatis-Plus 全局过滤掉了，恢复要走数据库操作，本来就不该是后台按钮。
 */
@Tag(name = "16-管理后台", description = "视频管理：短视频翻页、下架/恢复与删除")
@RestController
@RequestMapping("/api/admin/video")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_VIDEO_MANAGE)
public class AdminVideoController {

    private final AdminVideoService adminVideoService;

    @Operation(summary = "视频列表",
            description = "含已下架（status=2）；关键词按标题模糊匹配，纯数字时额外认上传者 ID；"
                    + "固定按发布时间倒序。注意 status 只有 1/2 两值，删除后的行整体隐身")
    @GetMapping("/page")
    public Result<PageResult<AdminVideoDTO>> page(AdminVideoQueryDTO query) {
        return Result.ok(adminVideoService.page(query));
    }

    @Operation(summary = "下架视频",
            description = "置 status=2，信息流不再展示但记录保留；必须带 reason 进服务端审计日志")
    @PostMapping("/{id}/offline")
    public Result<AdminVideoDTO> offline(@Parameter(description = "视频记录 ID") @PathVariable("id") Long id,
                                         @Parameter(description = "下架事由") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminVideoService.setStatus(id, false, SecurityUtil.getUserId(), reason), "视频已下架");
    }

    @Operation(summary = "恢复上架", description = "下架误操作或整改完成后置回 status=1；幂等")
    @PostMapping("/{id}/online")
    public Result<AdminVideoDTO> online(@Parameter(description = "视频记录 ID") @PathVariable("id") Long id) {
        return Result.ok(adminVideoService.setStatus(id, true, SecurityUtil.getUserId(), null), "视频已恢复上架");
    }

    @Operation(summary = "删除视频",
            description = "逻辑删除（deleted=1），上传者本人删与管理员删同一落点；文件本体一期不级联清理；幂等")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "视频记录 ID") @PathVariable("id") Long id) {
        adminVideoService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "视频已删除");
    }

    /* ==================== 二级分类字典（控制栏可增删改） ==================== */

    @Operation(summary = "分类列表",
            description = "视频板块顶部分类栏的全部项（含停用），按 sort 升序；用户端另走 /api/live/video/categories 只拉启用项")
    @GetMapping("/category/list")
    public Result<List<AdminVideoCategoryDTO>> categoryList() {
        return Result.ok(adminVideoService.listCategories());
    }

    @Operation(summary = "新增分类",
            description = "name 必填（未删除行内不允许同名）；sort 为空则排到最后；新分类默认启用")
    @PostMapping("/category")
    public Result<AdminVideoCategoryDTO> createCategory(
            @Parameter(description = "分类名称") @RequestParam("name") String name,
            @Parameter(description = "排序，空则排最后") @RequestParam(value = "sort", required = false) Integer sort) {
        return Result.ok(adminVideoService.createCategory(name, sort, SecurityUtil.getUserId()), "分类已新增");
    }

    @Operation(summary = "修改分类",
            description = "改名 / 调序 / 启停；参数不传表示该项不改。停用仅从前端分类栏隐身，不动历史视频的分类文本")
    @PutMapping("/category/{id}")
    public Result<AdminVideoCategoryDTO> updateCategory(
            @Parameter(description = "分类 ID") @PathVariable("id") Long id,
            @Parameter(description = "新名称，空不改") @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "新排序，空不改") @RequestParam(value = "sort", required = false) Integer sort,
            @Parameter(description = "状态 1 启用 0 停用，空不改") @RequestParam(value = "status", required = false) Integer status) {
        return Result.ok(adminVideoService.updateCategory(id, name, sort, status, SecurityUtil.getUserId()), "分类已保存");
    }

    @Operation(summary = "删除分类",
            description = "逻辑删除：仅从字典隐身，不动任何已发布视频的 category 文本；幂等")
    @DeleteMapping("/category/{id}")
    public Result<Void> deleteCategory(@Parameter(description = "分类 ID") @PathVariable("id") Long id) {
        adminVideoService.deleteCategory(id, SecurityUtil.getUserId());
        return Result.ok(null, "分类已删除");
    }
}
