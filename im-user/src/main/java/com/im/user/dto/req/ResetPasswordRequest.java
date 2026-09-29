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
 * 找回密码请求。
 *
 * <p>两条通道二选一：手机短信验证码或邮箱验证码。验证码在发码阶段就已按 {@code reset}
 * 场景写入 Redis（见 {@code RedisKeys.captchaSms}），因此登录用的验证码在这里一律无效——
 * 改密码这件事的信任级别必须高于登录。
 *
 * <p>{@code resetType} 决定校验哪个字段，跨字段约束放在服务层做（Bean Validation 表达不了
 * 「二选一必填」而不引入自定义注解，不值得为此多加一个类）。
 */
@Data
@Schema(description = "找回密码请求")
public class ResetPasswordRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 找回方式：手机验证码 */
    public static final String TYPE_PHONE = "phone";
    /** 找回方式：邮箱验证码 */
    public static final String TYPE_EMAIL = "email";

    @Schema(description = "找回方式：phone 手机验证码 / email 邮箱验证码", example = "phone",
            requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {TYPE_PHONE, TYPE_EMAIL})
    @NotBlank(message = "找回方式不能为空")
    private String resetType;

    @Schema(description = "注册时绑定的手机号，resetType=phone 时必填", example = "13800000001")
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "注册时绑定的邮箱，resetType=email 时必填", example = "user@qq.com")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "验证码，6 位数字，来自 /api/auth/password/sms-code 或 /email-code",
            example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;

    @Schema(description = "新密码，6-32 位", example = "newPass123", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String newPassword;

    /**
     * 是否走邮箱通道。
     */
    public boolean byEmail() {
        return TYPE_EMAIL.equalsIgnoreCase(resetType);
    }
}
