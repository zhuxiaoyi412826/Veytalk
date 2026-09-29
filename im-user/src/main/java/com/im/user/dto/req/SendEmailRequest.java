package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发送邮箱验证码入参。
 *
 * <p>通过 QQ 邮箱 SMTP 发送 6 位验证码；登录场景下邮箱未注册时会自动建号，
 * 找回密码场景则要求邮箱已绑定账号。
 */
@Data
@Schema(description = "发送邮箱验证码请求")
public class SendEmailRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场景：登录（未注册自动建号），常量取自 {@link SendSmsRequest} 以保持单一来源 */
    public static final String SCENE_LOGIN = SendSmsRequest.SCENE_LOGIN;
    /** 场景：找回密码 */
    public static final String SCENE_RESET = SendSmsRequest.SCENE_RESET;

    @Schema(description = "接收验证码的邮箱", example = "user@qq.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "使用场景：login / reset，默认 login；找回密码端点会强制改写为 reset",
            example = "login", allowableValues = {SCENE_LOGIN, SCENE_RESET})
    private String scene = SCENE_LOGIN;

    @Schema(description = "图形验证码键，来自 /api/captcha/image；开启闸门时必填")
    private String captchaKey;

    @Schema(description = "图形验证码答案；开启闸门时必填，校验通过后才发送邮件")
    private String captchaCode;
}
