package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminDictService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminIcebreakerDTO;
import com.im.common.domain.AdminTagDTO;
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
 * 后台字典：兴趣标签库（{@code im_tag}）与破冰文案库（{@code im_icebreaker}）。
 *
 * <p>挂在 {@code /api/admin/dating/dict} 子路径下但<b>是独立的一个类</b>：这两张表在同一条
 * 权限点（134 {@code system:tag:manage}）下、前端也是同一个菜单页，路径上顺着 dating 走便于归类；
 * 但绝不能把端点并进 {@code AdminDatingController}——Sa-Token 的类级与方法级
 * {@code @SaCheckPermission} 是「都要过」，并进去等于给字典操作额外加了一道
 * {@code system:dating:manage}。{@code /api/admin/dating/**} 已覆盖本前缀，Knife4j 无需再登记。
 *
 * <p>写操作全部用 {@code @RequestParam} 而不是 DTO：字段都是两三个短值，
 * 为一个三字段 body 单开一个类，换来的只是让前端多一层 JSON 拼装。
 */
@Tag(name = "16-管理后台", description = "字典管理：兴趣标签库与破冰文案库（打招呼模板/标签开场白/话题卡）")
@RestController
@RequestMapping("/api/admin/dating/dict")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_TAG_MANAGE)
public class AdminDictController {

    private final AdminDictService adminDictService;

    /* ==================== 兴趣标签 ==================== */

    @Operation(summary = "标签全量列表",
            description = "不分页——标签是下拉数据源，前端要一次性拿到才能做选择器与分类分组。"
                    + "不传 category 就返回全部（含已停用的，后台需要能看到并重新启用）")
    @GetMapping("/tag/list")
    public Result<List<AdminTagDTO>> listTags(
            @Parameter(description = "按分类过滤，如 outdoor / food") @RequestParam(value = "category", required = false) String category) {
        return Result.ok(adminDictService.listTags(category));
    }

    @Operation(summary = "新增标签",
            description = "同名校验在库里做，重名直接报错而不是静默改旧行；不传 sort 就排到本分类最后")
    @PostMapping("/tag")
    public Result<AdminTagDTO> createTag(
            @Parameter(description = "标签名，最长 32") @RequestParam("name") String name,
            @Parameter(description = "分类，最长 16") @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "图标标识，最长 32") @RequestParam(value = "icon", required = false) String icon,
            @Parameter(description = "排序，越小越靠前") @RequestParam(value = "sort", required = false) Integer sort) {
        return Result.ok(adminDictService.createTag(name, category, icon, sort, SecurityUtil.getUserId()), "标签已新增");
    }

    @Operation(summary = "修改标签",
            description = "只改传入的字段。status=0 为停用：<b>不删用户已有的绑定</b>，"
                    + "只是不再出现在选人标签池与匹配加权里，随时可再启用")
    @PutMapping("/tag/{id}")
    public Result<AdminTagDTO> updateTag(@Parameter(description = "标签 ID") @PathVariable("id") Long id,
                                         @Parameter(description = "标签名，最长 32") @RequestParam(value = "name", required = false) String name,
                                         @Parameter(description = "分类，最长 16") @RequestParam(value = "category", required = false) String category,
                                         @Parameter(description = "图标标识，最长 32") @RequestParam(value = "icon", required = false) String icon,
                                         @Parameter(description = "排序，越小越靠前") @RequestParam(value = "sort", required = false) Integer sort,
                                         @Parameter(description = "1 启用 0 停用") @RequestParam(value = "status", required = false) Integer status) {
        return Result.ok(adminDictService.updateTag(id, name, category, icon, sort, status, SecurityUtil.getUserId()),
                "标签已更新");
    }

    @Operation(summary = "删除标签",
            description = "逻辑删，并<b>级联删掉该标签的标签开场白</b>（那些文案绑在这个标签上，留着就是死数据）；"
                    + "用户资料上的标签绑定只留痕不再展示。幂等")
    @DeleteMapping("/tag/{id}")
    public Result<Void> deleteTag(@Parameter(description = "标签 ID") @PathVariable("id") Long id) {
        adminDictService.deleteTag(id, SecurityUtil.getUserId());
        return Result.ok(null, "标签已删除");
    }

    /* ==================== 破冰文案 ==================== */

    @Operation(summary = "破冰文案列表",
            description = "三类文案共用一屏，靠 type 区分：1 打招呼模板 2 话题卡 3 标签开场白（必须绑 tag）。"
                    + "固定按 type 再按 sort 升序，方便同分类连着看")
    @GetMapping("/icebreaker/page")
    public Result<PageResult<AdminIcebreakerDTO>> pageIcebreakers(
            @Parameter(description = "1 打招呼模板 2 话题卡 3 标签开场白") @RequestParam(value = "type", required = false) Integer type,
            @Parameter(description = "按绑定的标签过滤") @RequestParam(value = "tagId", required = false) Long tagId,
            @Parameter(description = "文案关键词，最长 50") @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(value = "current", defaultValue = "1") long current,
            @Parameter(description = "每页条数，上限 100") @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(adminDictService.pageIcebreakers(type, tagId, keyword, current, size));
    }

    @Operation(summary = "新增破冰文案",
            description = "type=3 时必须给出已存在的 tagId，否则报错——没有标签绑定的开场白在用户端永远取不出来")
    @PostMapping("/icebreaker")
    public Result<AdminIcebreakerDTO> createIcebreaker(
            @Parameter(description = "1 打招呼模板 2 话题卡 3 标签开场白") @RequestParam("type") Integer type,
            @Parameter(description = "绑定的标签 ID，仅 type=3 需要") @RequestParam(value = "tagId", required = false) Long tagId,
            @Parameter(description = "文案内容，最长 255") @RequestParam("content") String content,
            @Parameter(description = "排序，越小越靠前") @RequestParam(value = "sort", required = false) Integer sort) {
        return Result.ok(adminDictService.createIcebreaker(type, tagId, content, sort, SecurityUtil.getUserId()),
                "文案已新增");
    }

    @Operation(summary = "修改破冰文案",
            description = "只改传入的字段。把 type 从 3 改成 1/2 时不会顺手清掉旧 tagId——"
                    + "那个绑定对另外两类是无害冗余，清掉反而让「改回标签开场白」要重选一次")
    @PutMapping("/icebreaker/{id}")
    public Result<AdminIcebreakerDTO> updateIcebreaker(@Parameter(description = "文案 ID") @PathVariable("id") Long id,
                                                       @Parameter(description = "1 打招呼模板 2 话题卡 3 标签开场白") @RequestParam(value = "type", required = false) Integer type,
                                                       @Parameter(description = "绑定的标签 ID，仅 type=3 需要") @RequestParam(value = "tagId", required = false) Long tagId,
                                                       @Parameter(description = "文案内容，最长 255") @RequestParam(value = "content", required = false) String content,
                                                       @Parameter(description = "排序，越小越靠前") @RequestParam(value = "sort", required = false) Integer sort,
                                                       @Parameter(description = "1 启用 0 停用") @RequestParam(value = "status", required = false) Integer status) {
        return Result.ok(adminDictService.updateIcebreaker(id, type, tagId, content, sort, status, SecurityUtil.getUserId()),
                "文案已更新");
    }

    @Operation(summary = "删除破冰文案",
            description = "逻辑删。用户端取文案时本来就是随机抽样，删掉一条只会让候选少一条，不影响任何已有会话")
    @DeleteMapping("/icebreaker/{id}")
    public Result<Void> deleteIcebreaker(@Parameter(description = "文案 ID") @PathVariable("id") Long id) {
        adminDictService.deleteIcebreaker(id, SecurityUtil.getUserId());
        return Result.ok(null, "文案已删除");
    }
}
