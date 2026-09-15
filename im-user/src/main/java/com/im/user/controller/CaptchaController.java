package com.im.user.controller;

import com.im.common.api.Result;
import com.im.user.dto.req.SendEmailRequest;
import com.im.user.dto.req.SendSmsRequest;
import com.im.user.dto.vo.CaptchaImageVO;
import com.im.user.dto.vo.EmailSendVO;
import com.im.user.dto.vo.SmsSendVO;
import com.im.user.service.CaptchaService;
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
 * 验证码接口，全部匿名可访问（已在 {@code SaTokenConfigure} 白名单中）。
 */
@Tag(name = "01-验证码", description = "图形验证码与短信验证码")
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @Operation(summary = "获取图形验证码", description = "返回 Data URI 形式的 PNG，前端直接赋给 img.src；点击图片可刷新")
    @GetMapping("/image")
    public Result<CaptchaImageVO> image() {
        return Result.ok(captchaService.generateImage());
    }

    @Operation(summary = "发送短信验证码",
            description = "Mock 实现，不接真实短信服务商；开启闸门时需先传图形验证码；同一手机号 60 秒内只允许发送一次")
    @PostMapping("/sms")
    public Result<SmsSendVO> sms(@RequestBody @Valid SendSmsRequest request) {
        return Result.ok(captchaService.sendSms(request), "验证码已发送");
    }

    @Operation(summary = "发送邮箱验证码",
            description = "通过 QQ 邮箱 SMTP 发送 6 位验证码；同一邮箱 60 秒内只允许发送一次")
    @PostMapping("/email")
    public Result<EmailSendVO> email(@RequestBody @Valid SendEmailRequest request) {
        return Result.ok(captchaService.sendEmail(request), "验证码已发送");
    }
}
