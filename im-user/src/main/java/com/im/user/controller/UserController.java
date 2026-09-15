package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.req.BindPhoneRequest;
import com.im.user.dto.req.ChangePasswordRequest;
import com.im.user.dto.req.UpdateProfileRequest;
import com.im.user.dto.req.UserSearchQuery;
import com.im.user.dto.vo.UserCardVO;
import com.im.user.dto.vo.UserVO;
import com.im.user.service.AuthService;
import com.im.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户资料接口，全部需要登录（由 {@code SaTokenConfigure} 的路由拦截统一保证）。
 */
@Tag(name = "01-用户", description = "个人资料、密码、用户检索与在线状态")
@Validated
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@SaCheckLogin
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @Operation(summary = "查询本人资料", description = "返回含手机号、角色与权限码的完整资料")
    @GetMapping("/profile")
    public Result<UserVO> profile() {
        return Result.ok(userService.getProfile(SecurityUtil.getUserId()));
    }

    @Operation(summary = "修改本人资料", description = "只更新请求体中非 null 的字段")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@RequestBody @Valid UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(SecurityUtil.getUserId(), request), "资料已更新");
    }

    @Operation(summary = "修改密码", description = "logoutAll 为 true 时修改成功后所有设备都需要重新登录")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        Long userId = SecurityUtil.getUserId();
        userService.changePassword(userId, request);
        if (!Boolean.FALSE.equals(request.getLogoutAll())) {
            authService.logoutEverywhere(userId);
            return Result.ok(null, "密码已修改，请使用新密码重新登录");
        }
        return Result.ok(null, "密码已修改");
    }

    @Operation(summary = "绑定手机号", description = "凭短信验证码绑定或换绑手机号；验证码通过 /api/captcha/sms（scene=bind）获取，无需图形验证码")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @PutMapping("/phone")
    public Result<UserVO> bindPhone(@RequestBody @Valid BindPhoneRequest request) {
        Long userId = SecurityUtil.getUserId();
        userService.bindPhone(userId, request);
        return Result.ok(userService.getProfile(userId), "手机号已绑定");
    }

    @Operation(summary = "他人资料卡片", description = "不含手机号与邮箱，附带与当前用户的好友/拉黑关系")
    @GetMapping("/{id}/card")
    public Result<UserCardVO> card(@Parameter(description = "目标用户 ID") @PathVariable("id") Long id) {
        return Result.ok(userService.getCard(id, SecurityUtil.getUserId()));
    }

    @Operation(summary = "搜索用户", description = "按账号、昵称模糊匹配，手机号在 exact=true 时全等；结果自动排除自己")
    @GetMapping("/search")
    public Result<PageResult<UserCardVO>> search(@Valid UserSearchQuery query) {
        return Result.ok(userService.search(query, SecurityUtil.getUserId()));
    }

    @Operation(summary = "批量查询在线状态")
    @GetMapping("/online")
    public Result<Map<Long, Boolean>> online(
            @Parameter(description = "用户 ID 列表，逗号分隔") @RequestParam("userIds")
            @Size(max = 200, message = "单次最多查询 200 个用户") List<Long> userIds) {
        return Result.ok(userService.batchOnline(userIds));
    }
}
