package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminAiService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminAiOverviewDTO;
import com.im.common.domain.AdminAiUsageDTO;
import com.im.common.domain.AdminAiUsageQueryDTO;
import com.im.common.domain.AdminAiUserDTO;
import com.im.common.domain.AdminAiUserQueryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台 AI 用量管控（四期第六批）：付费大模型调用的账本与闸门。
 *
 * <p>权限单设 {@code system:ai:guard}：这一屏能看到「谁在什么时间调了模型多久、
 * 失败原因是什么」的明细，也能封禁个人的 AI 能力，与「系统配置」「面试记录」各自独立。
 *
 * <p>封禁只停「调用付费模型」这一项能力，<b>不动账号登录</b>——这是与
 * {@code /api/admin/user/{id}/disable} 的分界线：禁号是有留痕的重处置，
 * 而「预算花完还来烧钱」大多数时候只需要停掉这一项能力就够了。
 * 封禁即时生效（护栏每次模型调用现查库），解封同样即时，无需重启或清缓存。
 */
@Tag(name = "16-管理后台", description = "AI 用量管控：付费模型调用的用量账本与封禁闸门")
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_AI_GUARD)
public class AdminAiController {

    private final AdminAiService adminAiService;

    @Operation(summary = "概览数字",
            description = "护栏运行态（开关/预算）+ 全历史与今日的调用次数、耗时、去重人数 + 封禁人数 + "
                    + "烧钱榜前 10 人。日界由 MySQL 的 CURDATE() 定；耗时口径是模型调用实际毫秒数")
    @GetMapping("/overview")
    public Result<AdminAiOverviewDTO> overview() {
        return Result.ok(adminAiService.overview());
    }

    @Operation(summary = "按人聚合翻页",
            description = "谁烧了多少：累计耗时倒序。budgetMs/remainingMs 是按当前配置算的快照；"
                    + "bannedOnly=true 只看被封禁的人；userId 精确筛某人（从用户管理拿 ID）")
    @GetMapping("/users")
    public Result<PageResult<AdminAiUserDTO>> pageUsers(AdminAiUserQueryDTO query) {
        return Result.ok(adminAiService.pageUsers(query));
    }

    @Operation(summary = "调用明细翻页",
            description = "一笔模型调用一行，时间倒序；success=0 的行只留痕不扣预算，"
                    + "errorMsg 是失败原因摘要。可按 userId/scene/success 过滤")
    @GetMapping("/usage")
    public Result<PageResult<AdminAiUsageDTO>> pageUsage(AdminAiUsageQueryDTO query) {
        return Result.ok(adminAiService.pageUsage(query));
    }

    @Operation(summary = "封禁 AI 功能",
            description = "只停这个账号调用付费模型的能力，不影响登录与聊天；即时生效。"
                    + "幂等：已封禁再调只更新原因。reason 会展示给被封者（文案见 10008）")
    @PostMapping("/{userId}/ban")
    public Result<Void> ban(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId,
                            @Parameter(description = "封禁原因，留空用默认文案")
                            @RequestParam(value = "reason", required = false) String reason) {
        adminAiService.ban(userId, reason);
        return Result.ok(null, "已封禁该账号的 AI 功能");
    }

    @Operation(summary = "解除 AI 封禁",
            description = "即时恢复调用；幂等：没封过直接返回成功")
    @PostMapping("/{userId}/unban")
    public Result<Void> unban(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        adminAiService.unban(userId);
        return Result.ok(null, "已解除该账号的 AI 封禁");
    }
}
