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
 * <p>通过 QQ 邮箱 SMTP 发送 6 位验证码；邮箱未注册时，凭验证码登录会自动建号。
 */
@Data
@Schema(description = "发送邮箱验证码请求")
public class SendEmailRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "接收验证码的邮箱", example = "user@qq.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "图形验证码键，来自 /api/captcha/image；开启闸门时必填")
    private String captchaKey;

    @Schema(description = "图形验证码答案；开启闸门时必填，校验通过后才发送邮件")
    private String captchaCode;
}
