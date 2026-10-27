package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.AdminUserQuery;
import com.im.admin.dto.vo.AdminUserVO;
import com.im.admin.service.AdminUserService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
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
 * 后台的用户管理。
 *
 * <p>一期只有四个端点：找人、看档案、禁用、恢复。<b>没有改资料和改密码的端点</b>——
 * 用户自己的资料有自助入口（{@code /api/user}），后台代改会绕过昵称与头像的敏感词校验，
 * 而代改密码等于把「知道账号即可接管账号」这件事做成了常规操作。真要加，
 * 得先补审计表，让每一次代改都留下「谁改的、改成什么、为什么」。
 *
 * <p>禁用与恢复拆成两个 GET 之外的 POST 而不是一个带 boolean 的 PUT：
 * 两者的前置校验不同（禁用必须写事由、恢复不必），拆开后 URL 本身就说明了在做什么，
 * 排查时日志里不会出现「PUT /user/123/disabled?disabled=false」这种要靠参数值才读得懂的记录。
 *
 * <p>二期把权限点从伞权限 {@code system:manage} 细化到 {@code system:user:manage}：
 * 管人与处置举报是两种不同的职责，拆开后端只给「看板 + 管人」的运营（operator）就能禁/启账号，
 * 而不顺手拿到举报处置权。admin 同时持有伞权限与本点，不受影响。
 */
@Tag(name = "16-管理后台", description = "用户管理：检索、档案、禁用与恢复")
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_USER_MANAGE)
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "用户列表",
            description = "关键词按账号/昵称/手机号匹配（由 im-user 执行，手机号需全号前缀），固定按注册时间倒序；"
                    + "每行带未处置工单数与在线状态，手机号为脱敏值")
    @GetMapping("/page")
    public Result<PageResult<AdminUserVO>> page(AdminUserQuery query) {
        return Result.ok(adminUserService.page(query));
    }

    @Operation(summary = "用户档案", description = "单个用户的后台视角档案，含积分余额与未处置工单数")
    @GetMapping("/{userId}")
    public Result<AdminUserVO> detail(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        return Result.ok(adminUserService.detail(userId));
    }

    @Operation(summary = "禁用账号",
            description = "置 im_user.status=0 并即时踢掉该用户的全部长连接（不踢的话他手上的连接还能继续收发，"
                    + "禁用要等下次登录才生效）；必须写事由，进服务端日志。不能禁自己。"
                    + "minutes 不传为永久封禁，传了则到期由 im-user 的定时任务自动解封（上限 365 天）；"
                    + "对已禁用的账号再调一次等于改期")
    @PostMapping("/{userId}/disable")
    public Result<AdminUserVO> disable(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId,
                                      @Parameter(description = "禁用事由，会写进服务端日志")
                                      @RequestParam(value = "reason", required = false) String reason,
                                      @Parameter(description = "封禁时长（分钟）；留空表示永久封禁")
                                      @RequestParam(value = "minutes", required = false) Long minutes) {
        return Result.ok(adminUserService.setDisabled(userId, true, SecurityUtil.getUserId(), reason, minutes), "账号已禁用");
    }

    @Operation(summary = "恢复账号",
            description = "解除禁用并一并清空到期时间（提前解封后留着旧日期会在列表里显示成一个作废的时间）；"
                    + "恢复后不会自动重连，需要用户重新登录")
    @PostMapping("/{userId}/enable")
    public Result<AdminUserVO> enable(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        return Result.ok(adminUserService.setDisabled(userId, false, SecurityUtil.getUserId(), null, null), "账号已恢复");
    }
}
