package com.im.group.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.group.dto.req.CreateRobotRequest;
import com.im.group.dto.req.RobotWebhookRequest;
import com.im.group.dto.req.UpdateRobotRequest;
import com.im.group.dto.vo.GroupRobotVO;
import com.im.group.service.GroupRobotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * 群机器人与 Webhook 接口。
 *
 * <p>本类同时含「需登录的管理端」与「匿名的 webhook 端点」：全局 Sa-Token 拦截器覆盖 {@code /api/**}
 * 并要求登录，唯一例外是白名单里的 {@code /api/robot/webhook/**}（见 {@code SaTokenConfigure}）。
 * 因此管理端方法靠默认拦截器兜底登录，webhook 方法靠 token 认证 + 按 IP 限流兜底。
 */
@Tag(name = "05b-群机器人", description = "群机器人管理与外部系统 Webhook 回调")
@RestController
@RequestMapping("/api/robot")
@RequiredArgsConstructor
public class GroupRobotController {

    private final GroupRobotService groupRobotService;

    @Operation(summary = "创建群机器人", description = "群主与管理员可操作；生成全局唯一 webhook token 与回调地址")
    @SaCheckLogin
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "robot.create")
    @PostMapping
    public Result<GroupRobotVO> create(@RequestBody @Valid CreateRobotRequest request,
                                       @Parameter(description = "前端站点根地址，用于拼回调 URL")
                                       @RequestParam(value = "base", required = false) String base) {
        return Result.ok(groupRobotService.create(SecurityUtil.getUserId(), request, base), "机器人已创建");
    }

    @Operation(summary = "群机器人列表", description = "仅群主与管理员可看，返回每个机器人的 webhook 地址")
    @SaCheckLogin
    @GetMapping("/group/{groupId}")
    public Result<List<GroupRobotVO>> list(@Parameter(description = "群 ID") @PathVariable("groupId") Long groupId,
                                           @RequestParam(value = "base", required = false) String base) {
        return Result.ok(groupRobotService.list(SecurityUtil.getUserId(), groupId, base));
    }

    @Operation(summary = "修改群机器人", description = "群主与管理员可操作；字段传 null 不改，空串表示清空")
    @SaCheckLogin
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "robot.update")
    @PutMapping("/{robotId}")
    public Result<GroupRobotVO> update(@Parameter(description = "机器人 ID") @PathVariable("robotId") Long robotId,
                                       @RequestBody @Valid UpdateRobotRequest request,
                                       @RequestParam(value = "base", required = false) String base) {
        return Result.ok(groupRobotService.update(SecurityUtil.getUserId(), robotId, request, base), "机器人已更新");
    }

    @Operation(summary = "重置 webhook 密钥", description = "群主与管理员可操作；旧回调地址立刻失效，外部系统需改用新地址")
    @SaCheckLogin
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "robot.reset")
    @PostMapping("/{robotId}/token/reset")
    public Result<GroupRobotVO> resetToken(@Parameter(description = "机器人 ID") @PathVariable("robotId") Long robotId,
                                           @RequestParam(value = "base", required = false) String base) {
        return Result.ok(groupRobotService.resetToken(SecurityUtil.getUserId(), robotId, base), "密钥已重置");
    }

    @Operation(summary = "删除群机器人", description = "群主与管理员可操作；逻辑删除，token 随即失效")
    @SaCheckLogin
    @DeleteMapping("/{robotId}")
    public Result<Void> delete(@Parameter(description = "机器人 ID") @PathVariable("robotId") Long robotId) {
        groupRobotService.delete(SecurityUtil.getUserId(), robotId);
        return Result.ok(null, "机器人已删除");
    }

    /**
     * 外部系统回调入口（匿名，凭 token 认证）。token 即凭证，路径不在登录白名单内即无法访问；
     * 按 IP 限流兜住被拿到 token 后的刷屏。
     */
    @Operation(summary = "Webhook 发送消息", description = "外部系统凭回调地址 POST 文本到群，无需登录，token 即凭证")
    @RateLimit(count = 30, seconds = 60, key = "robot.webhook")
    @PostMapping("/webhook/{token}")
    public Result<Void> webhook(@Parameter(description = "webhook token") @PathVariable("token") String token,
                                @RequestBody @Valid RobotWebhookRequest request) {
        groupRobotService.sendByWebhook(token, request);
        return Result.ok(null, "消息已发送");
    }
}
