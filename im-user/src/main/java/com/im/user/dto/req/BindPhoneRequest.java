package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 绑定手机号入参。
 *
 * <p>调用方已登录、是本人操作，因此发送验证码时（{@code scene=bind}）不再要求图形验证码，
 * 这里只需手机号 + 短信验证码即可完成首绑或换绑。
 */
@Data
@Schema(description = "绑定手机号请求")
public class BindPhoneRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "待绑定的手机号", example = "13800000001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "短信验证码，通过 /api/captcha/sms?scene=bind 获取", example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;
}
