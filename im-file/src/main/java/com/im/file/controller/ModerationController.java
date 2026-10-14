package com.im.file.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.file.dto.req.ModerationReportReq;
import com.im.file.dto.vo.ModerationPolicyVO;
import com.im.file.service.ModerationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图片内容审核接口。
 *
 * <p>挂在 {@code /api/file/moderation} 而不是单开一个 {@code /api/moderation}：
 * 审核属于「图片进本站」这一段的事，与文件域同源同生命周期；
 * 更实际的理由是 Knife4j 的分组按路径前缀切（{@code /api/file/**}），
 * 单开前缀会让这两个端点从接口文档里凭空消失——文档丢了不比接口坏了更难发现。
 *
 * <p{@code /report} 的配额故意很松（每分钟 30 次）：命中上报是低频事件，
 * 而它一旦被限流掐掉，丢的是审计记录本身。前端对这个请求是尽力而为，
 * 失败不重试，所以这里也不需要为了防止刷爆而卡得很死。
 */
@Slf4j
@RestController
@RequestMapping("/api/file/moderation")
@RequiredArgsConstructor
public class ModerationController {

    private final ModerationService moderationService;

    @Operation(summary = "审核策略", description = "返回总开关与阈值，发送端据此决定要不要识别、按什么判违规")
    @SaCheckLogin
    @GetMapping("/policy")
    public Result<ModerationPolicyVO> policy() {
        return Result.ok(moderationService.policy());
    }

    @Operation(summary = "命中上报", description = "发送端识别到违规图片并拒发后上报判定结果，只落审计通道，服务端不采信其结论")
    @SaCheckLogin
    @RateLimit(count = 30, dimension = RateLimit.Dimension.USER, key = "file.moderation.report")
    @PostMapping("/report")
    public Result<Void> report(@Valid @RequestBody ModerationReportReq req) {
        moderationService.report(SecurityUtil.getUserId(), req);
        return Result.ok();
    }
}
