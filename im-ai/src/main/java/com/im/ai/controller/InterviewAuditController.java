package com.im.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.ai.dto.req.InterviewEndRequest;
import com.im.ai.dto.req.InterviewEventReportRequest;
import com.im.ai.dto.req.InterviewTurnRequest;
import com.im.ai.entity.InterviewSession;
import com.im.ai.service.InterviewAuditService;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 面试监考与审计接口。
 *
 * <p>与 {@link InterviewController} 分开：那个是对话通道（SSE、消耗模型额度、
 * 慢且长连接），这些是纯写入的小接口（毫秒级、可批量、要接受高频上报）。
 * 混在一个类里会让人以为上报事件也在等模型，实际上两者毫无关系。
 *
 * <p>上报接口限流放得很宽（120 次/分钟）：事件是攒批定时 flush 的，
 * 正常一场面试一分钟最多几次上报，宽裕的名额是留给「页面隐藏时强制补发」
 * 与网络重试的——把这些也卡住，最关键的离开时刻反而记不上。
 */
@Tag(name = "11-面试监考与审计", description = "面试会话留痕、切屏/粘贴等违规事件上报与事后复盘")
@RestController
@RequestMapping("/api/ai/interview")
@RequiredArgsConstructor
@SaCheckLogin
public class InterviewAuditController {

    private final InterviewAuditService auditService;

    @Operation(summary = "开始面试（建会话）",
            description = "返回 sessionId 与监考策略；后续的事件上报与问答落库都要带上这个 ID。"
                    + "同时会把该用户名下未收尾的旧会话补记为「未完成」")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "ai.interview.start")
    @PostMapping("/session/start")
    public Result<Map<String, Object>> start(@RequestParam(required = false) String title,
                                             @RequestParam(required = false) String screen) {
        return Result.ok(auditService.start(SecurityUtil.getUserId(), title, screen));
    }

    @Operation(summary = "批量上报监考事件",
            description = "事件只报「发生了什么」，是否计违规由服务端按配置判定；"
                    + "返回最新计数与 reachedLimit/ended，ended=true 时前端必须立即终止面试")
    @RateLimit(count = 120, seconds = 60, dimension = RateLimit.Dimension.USER, key = "ai.interview.events")
    @PostMapping("/events")
    public Result<Map<String, Object>> reportEvents(@RequestBody @Valid InterviewEventReportRequest request) {
        return Result.ok(auditService.report(SecurityUtil.getUserId(), request));
    }

    @Operation(summary = "落一轮问答",
            description = "每轮 SSE 收到 done 后调用，把「面试官的提问 + 候选人的作答」各写一行；重复上报按幂等忽略")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "ai.interview.turn")
    @PostMapping("/turn")
    public Result<Map<String, Object>> appendTurn(@RequestBody @Valid InterviewTurnRequest request) {
        return Result.ok(auditService.appendTurn(SecurityUtil.getUserId(), request));
    }

    @Operation(summary = "结束面试",
            description = "幂等：已结束的直接返回既有结论，不会被后到的 user-end 覆盖违规强制结束的原因")
    @PostMapping("/session/end")
    public Result<Map<String, Object>> end(@RequestBody @Valid InterviewEndRequest request) {
        return Result.ok(auditService.end(SecurityUtil.getUserId(), request));
    }

    @Operation(summary = "我的面试记录分页",
            description = "含各项违规计数与时长，可按 status 筛选（0 进行中 / 1 正常结束 / 2 违规强制结束 / 3 未完成）")
    @GetMapping("/session/page")
    public Result<Page<InterviewSession>> page(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) Integer status) {
        return Result.ok(auditService.pageMine(SecurityUtil.getUserId(), current, Math.min(size, 100), status));
    }

    @Operation(summary = "单场面试复盘",
            description = "会话汇总 + 违规构成 + 逐轮问答 + 事件时间线（事件上限 500 条）")
    @GetMapping("/session/{id}")
    public Result<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return Result.ok(auditService.detail(SecurityUtil.getUserId(), id));
    }
}
