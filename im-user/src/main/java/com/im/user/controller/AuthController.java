package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.user.dto.req.EmailLoginRequest;
import com.im.user.dto.req.LoginRequest;
import com.im.user.dto.req.RegisterRequest;
import com.im.user.dto.req.SmsLoginRequest;
import com.im.user.dto.vo.LoginVO;
import com.im.user.dto.vo.UserVO;
import com.im.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>{@code register} / {@code login} / {@code login/sms} 三个匿名接口已在
 * {@code SaTokenConfigure} 的路由白名单中放行；{@code logout} / {@code refresh} / {@code me}
 * 同属 {@code /api/auth}，但通过 {@link SaCheckLogin} 注解要求登录态——
 * 这也是「白名单只放在 SaRouter 层、不放在 Spring MVC 拦截器排除列表」的原因。
 *
 * <p>四个匿名入口全部标了按 IP 的 {@code @RateLimit}：此时还没有可信的用户身份，
 * 只能按来源地址限。登录另有图形验证码闸门，限流拦的是「绕过页面直接刷接口」的脚本。
 */
@Tag(name = "01-认证", description = "注册、登录、注销与续签")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册", description = "注册成功后直接返回登录态，前端无需再次登录")
    @RateLimit(count = 5, key = "auth.register")
    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody @Valid RegisterRequest request) {
        return Result.ok(authService.register(request), "注册成功");
    }

    @Operation(summary = "账号密码登录", description = "loginType 为 phone 时 account 传手机号，否则传账号")
    @RateLimit(count = 10, key = "auth.login")
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return Result.ok(authService.login(request), "登录成功");
    }

    @Operation(summary = "短信验证码登录", description = "手机号未注册时自动建号后登录")
    @RateLimit(count = 10, key = "auth.login.sms")
    @PostMapping("/login/sms")
    public Result<LoginVO> loginBySms(@RequestBody @Valid SmsLoginRequest request) {
        return Result.ok(authService.loginBySms(request), "登录成功");
    }

    @Operation(summary = "邮箱验证码登录", description = "邮箱未注册时自动建号后登录")
    @RateLimit(count = 10, key = "auth.login.email")
    @PostMapping("/login/email")
    public Result<LoginVO> loginByEmail(@RequestBody @Valid EmailLoginRequest request) {
        return Result.ok(authService.loginByEmail(request), "登录成功");
    }

    @Operation(summary = "注销当前设备")
    @SaCheckLogin
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok(null, "已退出登录");
    }

    @Operation(summary = "续签", description = "换发新 token 并注销旧 token，返回体结构与登录一致")
    @SaCheckLogin
    @PostMapping("/refresh")
    public Result<LoginVO> refresh() {
        return Result.ok(authService.refresh());
    }

    @Operation(summary = "当前登录用户资料")
    @SaCheckLogin
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser());
    }
}
