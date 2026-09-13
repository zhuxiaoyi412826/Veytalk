package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 注册入参。
 */
@Data
@Schema(description = "注册请求")
public class RegisterRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "登录账号，4-32 位字母数字下划线", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{3,31}$", message = "账号需以字母开头，由 4-32 位字母、数字或下划线组成")
    private String username;

    @Schema(description = "密码，6-32 位", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    @Schema(description = "昵称，留空时默认取账号", example = "爱丽丝")
    @Size(max = 32, message = "昵称最长 32 个字符")
    private String nickname;

    @Schema(description = "手机号，选填，填写后可用短信验证码登录", example = "13800000001")
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "邮箱，选填", example = "alice@im.local")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "图形验证码键，来自 /api/captcha/image")
    private String captchaKey;

    @Schema(description = "图形验证码答案")
    private String captchaCode;
}
