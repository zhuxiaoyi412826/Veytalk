package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.FeedbackQuery;
import com.im.admin.dto.req.FeedbackReplyRequest;
import com.im.admin.dto.vo.FeedbackVO;
import com.im.admin.enums.FeedbackType;
import com.im.admin.service.FeedbackService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台意见反馈管理：检索 / 查看 / 回复 / 删除。
 *
 * <p>独立权限点 {@code system:feedback}（ID 127）。执行迁移 SQL 后记得清 {@code im:auth:perm:*}
 * 缓存，否则 admin 在缓存到期前（TTL 30 分钟且登录不刷新）看不到新点，
 * 新控制器会把老管理员拦在门外回 code=1003（步骤写在 migration_feedback.sql）。
 *
 * <p>没有「新建/编辑」端点：反馈由前台用户产生，后台只能回复与删除——
 * 允许后台凭空造一条反馈或改用户原文，台账就失去了「用户到底说了什么」的凭证价值。
 */
@Tag(name = "16-管理后台", description = "意见反馈：用户提交的反馈检索、回复与删除")
@RestController
@RequestMapping("/api/admin/feedback")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_FEEDBACK)
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "反馈类型码表", description = "下拉选项由服务端码表给出（code+name），"
            + "前端不自造类型串：类型是列表过滤键，自造码会让台账统计对不上")
    @GetMapping("/types")
    public Result<List<Map<String, String>>> types() {
        List<Map<String, String>> list = Arrays.stream(FeedbackType.values())
                .map(t -> {
                    Map<String, String> item = new LinkedHashMap<>(2);
                    item.put("code", t.getCode());
                    item.put("name", t.getDisplayName());
                    return item;
                })
                .toList();
        return Result.ok(list);
    }

    @Operation(summary = "反馈列表", description = "按创建时间倒序；关键词在正文与联系方式上模糊匹配，"
            + "可按类型/状态过滤。提交人昵称经 AdminUserSpi 批量解析，账号注销显示「已注销」")
    @GetMapping("/page")
    public Result<PageResult<FeedbackVO>> page(FeedbackQuery query) {
        return Result.ok(feedbackService.adminPage(query));
    }

    @Operation(summary = "反馈详情")
    @GetMapping("/{id}")
    public Result<FeedbackVO> detail(@Parameter(description = "反馈 ID") @PathVariable("id") Long id) {
        return Result.ok(feedbackService.detail(id));
    }

    @Operation(summary = "回复反馈", description = "写入回复即把该条置为已回复并记回复人/时刻；"
            + "再次回复覆盖旧文（反馈是客服台账，不做回复版本化）")
    @PostMapping("/{id}/reply")
    public Result<FeedbackVO> reply(@Parameter(description = "反馈 ID") @PathVariable("id") Long id,
                                    @RequestBody @Valid FeedbackReplyRequest request) {
        return Result.ok(feedbackService.reply(id, request, SecurityUtil.getUserId()), "已回复");
    }

    @Operation(summary = "删除反馈", description = "物理删行（清理已处置完的台账）；不启逻辑删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "反馈 ID") @PathVariable("id") Long id) {
        feedbackService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "已删除");
    }
}
