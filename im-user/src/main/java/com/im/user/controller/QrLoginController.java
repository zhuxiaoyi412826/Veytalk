package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.req.QrCreateRequest;
import com.im.user.dto.req.QrSceneRequest;
import com.im.user.dto.vo.QrLoginCreateVO;
import com.im.user.dto.vo.QrLoginStatusVO;
import com.im.user.service.QrLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网页端扫码登录接口。
 *
 * <p>四个端点分两组信任级别：
 * <ul>
 *   <li>{@code create} / {@code status} 由未登录的网页调用，已加入 {@code SaTokenConfigure} 白名单，
 *       按 IP 限流；</li>
 *   <li>{@code scanned} / {@code authorize} 由已登录的 APP 调用，标 {@link SaCheckLogin}，
 *       授权用户取自当前登录态，不接受报文传入，杜绝替他人授权。</li>
 * </ul>
 * APP 侧扫码 / 确认逻辑由客户端实现，本控制器只提供后端契约端点。
 */
@Tag(name = "01-认证", description = "网页端扫码登录")
@RestController
@RequestMapping("/api/auth/qr-login")
@RequiredArgsConstructor
public class QrLoginController {

    private final QrLoginService qrLoginService;

    @Operation(summary = "生成登录二维码", description = "网页匿名调用，返回 scene 与 base64 二维码")
    @RateLimit(count = 20, key = "auth.qr.create")
    @PostMapping("/create")
    public Result<QrLoginCreateVO> create(@RequestBody(required = false) QrCreateRequest request) {
        String base = request == null ? null : request.getBase();
        return Result.ok(qrLoginService.create(base));
    }

    @Operation(summary = "轮询扫码状态", description = "网页匿名轮询；已授权时返回一次性登录包并作废二维码")
    @RateLimit(count = 120, seconds = 60, key = "auth.qr.status")
    @GetMapping("/status")
    public Result<QrLoginStatusVO> status(@RequestParam("scene") String scene) {
        return Result.ok(qrLoginService.poll(scene));
    }

    @Operation(summary = "标记已扫码", description = "APP 扫码后调用，把二维码推进到待确认")
    @SaCheckLogin
    @PostMapping("/scanned")
    public Result<Void> scanned(@RequestBody @Valid QrSceneRequest request) {
        qrLoginService.markScanned(request.getScene());
        return Result.ok(null, "已扫码，请在手机确认");
    }

    @Operation(summary = "确认授权登录", description = "APP 用户点确认后调用，为网页签发 web 设备登录态")
    @SaCheckLogin
    @PostMapping("/authorize")
    public Result<Void> authorize(@RequestBody @Valid QrSceneRequest request) {
        qrLoginService.authorize(request.getScene(), SecurityUtil.getUserId());
        return Result.ok(null, "授权成功");
    }
}
