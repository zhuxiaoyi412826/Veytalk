package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.admin.dto.req.FeedbackSubmitRequest;
import com.im.admin.dto.vo.FeedbackVO;
import com.im.admin.service.FeedbackService;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台意见反馈提交（设置页）。
 *
 * <p>登录态可达（非白名单），但<b>不设权限点</b>：任何登录用户都能提反馈，
 * 防刷靠 {@link RateLimit} 的按用户维度——反馈是「有话想说」的口子，
 * 加权限点等于把大多数用户挡在门外，与功能初衷相反。
 *
 * <p>提交人一律取登录态（{@code SecurityUtil.getUserId()}），不接受入参里的 userId：
 * 让调用方传 userId 等于允许替别人提反馈、往台账里塞伪造来源。
 */
@Tag(name = "18-意见反馈", description = "前台用户提交意见反馈")
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@SaCheckLogin
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "提交意见反馈",
            description = "设置页表单提交：类型（bug/suggestion/experience/other）+ 正文（≤1000 字）+ 联系方式（选填）。"
                    + "按用户限流，1 分钟最多 5 条，防止脚本刷台账")
    @RateLimit(count = 5, seconds = 60, dimension = RateLimit.Dimension.USER, key = "feedback.submit")
    @PostMapping
    public Result<FeedbackVO> submit(@RequestBody @Valid FeedbackSubmitRequest request) {
        return Result.ok(feedbackService.submit(request, SecurityUtil.getUserId()), "反馈已提交，感谢你的意见");
    }
}
