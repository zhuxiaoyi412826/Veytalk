package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminArticleService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminArticleDTO;
import com.im.common.domain.AdminArticleQueryDTO;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的文章管理：全状态翻页、详情、代写、编辑、审核通过/驳回、上下架与删除。
 *
 * <p>审核与上下架拆成独立 POST 而不是一个带枚举的 PUT，沿用 {@code AdminVideoController} 的
 * offline/online 分路口径：URL 自己说明在做什么，审计日志里不出现要靠参数值才读得懂的行。
 *
 * <p><b>删除是逻辑删除且不可自助恢复</b>（一期没有回收站页），所以前端把删除做成需要二次确认的
 * 高危动作；这里不做「删了还能查」的接口——{@code deleted=1} 的行 MyBatis-Plus 全局过滤掉了。
 */
@Tag(name = "16-管理后台", description = "文章管理：翻页、详情、代写/编辑、审核、上下架与删除")
@RestController
@RequestMapping("/api/admin/article")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_ARTICLE_MANAGE)
public class AdminArticleController {

    private final AdminArticleService adminArticleService;

    @Operation(summary = "文章列表",
            description = "含全状态（草稿/待审/已发布/驳回/已下架）；关键词按标题模糊，纯数字时额外认作者 ID；"
                    + "固定按创建时间倒序；列表项不含正文")
    @GetMapping("/page")
    public Result<PageResult<AdminArticleDTO>> page(AdminArticleQueryDTO query) {
        return Result.ok(adminArticleService.page(query));
    }

    @Operation(summary = "文章详情", description = "含正文，供后台预览与编辑回填")
    @GetMapping("/{id}")
    public Result<AdminArticleDTO> detail(@Parameter(description = "文章 ID") @PathVariable("id") Long id) {
        return Result.ok(adminArticleService.detail(id));
    }

    @Operation(summary = "代写文章",
            description = "publishNow=true 直接发布（官方内容免审），false 落待审走审核；作者缺省记为操作者本人")
    @PostMapping
    public Result<AdminArticleDTO> create(@RequestBody AdminArticleDTO dto,
                                          @Parameter(description = "是否直接发布") @RequestParam(value = "publishNow", defaultValue = "false") boolean publishNow) {
        return Result.ok(adminArticleService.create(dto, SecurityUtil.getUserId(), publishNow), "文章已创建");
    }

    @Operation(summary = "编辑文章", description = "改标题/摘要/正文/封面；不动状态（状态走审核与上下架）")
    @PutMapping("/{id}")
    public Result<AdminArticleDTO> update(@Parameter(description = "文章 ID") @PathVariable("id") Long id,
                                          @RequestBody AdminArticleDTO dto) {
        dto.setId(id);
        return Result.ok(adminArticleService.update(dto, SecurityUtil.getUserId()), "文章已保存");
    }

    @Operation(summary = "审核通过", description = "待审(1)→已发布(2) 并盖发布时间；必须带 reason 进审计日志")
    @PostMapping("/{id}/approve")
    public Result<AdminArticleDTO> approve(@Parameter(description = "文章 ID") @PathVariable("id") Long id,
                                           @Parameter(description = "审核备注") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminArticleService.review(id, true, reason, SecurityUtil.getUserId()), "文章已通过并发布");
    }

    @Operation(summary = "审核驳回", description = "待审(1)→驳回(3) 并写 rejectReason，作者可见后可改后重提")
    @PostMapping("/{id}/reject")
    public Result<AdminArticleDTO> reject(@Parameter(description = "文章 ID") @PathVariable("id") Long id,
                                          @Parameter(description = "驳回事由") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminArticleService.review(id, false, reason, SecurityUtil.getUserId()), "文章已驳回");
    }

    @Operation(summary = "下架文章", description = "已发布(2)→已下架(4)，广场不再展示但记录保留；带 reason 进审计日志")
    @PostMapping("/{id}/offline")
    public Result<AdminArticleDTO> offline(@Parameter(description = "文章 ID") @PathVariable("id") Long id,
                                           @Parameter(description = "下架事由") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminArticleService.setOffline(id, true, SecurityUtil.getUserId(), reason), "文章已下架");
    }

    @Operation(summary = "恢复上架", description = "已下架(4)→已发布(2)；幂等")
    @PostMapping("/{id}/online")
    public Result<AdminArticleDTO> online(@Parameter(description = "文章 ID") @PathVariable("id") Long id) {
        return Result.ok(adminArticleService.setOffline(id, false, SecurityUtil.getUserId(), null), "文章已恢复上架");
    }

    @Operation(summary = "删除文章", description = "逻辑删除（deleted=1），作者本人删与管理员删同一落点；幂等")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "文章 ID") @PathVariable("id") Long id) {
        adminArticleService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "文章已删除");
    }
}
