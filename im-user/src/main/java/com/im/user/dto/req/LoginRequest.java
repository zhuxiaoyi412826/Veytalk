package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账号密码登录入参。
 */
@Data
@Schema(description = "登录请求")
public class LoginRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 登录方式：账号 */
    public static final String TYPE_USERNAME = "username";
    /** 登录方式：手机号 */
    public static final String TYPE_PHONE = "phone";

    @Schema(description = "登录方式：username 账号 / phone 手机号，默认 username", example = "username",
            allowableValues = {TYPE_USERNAME, TYPE_PHONE})
    private String loginType = TYPE_USERNAME;

    @Schema(description = "账号或手机号", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "账号不能为空")
    private String account;

    @Schema(description = "密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    private String password;

    @Schema(description = "图形验证码键，来自 /api/captcha/image")
    private String captchaKey;

    @Schema(description = "图形验证码答案")
    private String captchaCode;

    @Schema(description = "设备标识：web / pc / android / ios / mini，默认 web", example = "web")
    private String deviceId;

    /**
     * 是否按手机号登录。
     */
    public boolean byPhone() {
        return TYPE_PHONE.equalsIgnoreCase(loginType);
    }
}
