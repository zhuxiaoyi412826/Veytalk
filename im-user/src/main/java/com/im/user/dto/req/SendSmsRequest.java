package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发送短信验证码入参。
 */
@Data
@Schema(description = "发送短信验证码请求")
public class SendSmsRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场景：登录（未注册自动注册） */
    public static final String SCENE_LOGIN = "login";
    /** 场景：注册校验 */
    public static final String SCENE_REGISTER = "register";
    /** 场景：绑定手机号 */
    public static final String SCENE_BIND = "bind";

    @Schema(description = "手机号", example = "13800000001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "使用场景：login / register / bind，默认 login", example = "login",
            allowableValues = {SCENE_LOGIN, SCENE_REGISTER, SCENE_BIND})
    private String scene = SCENE_LOGIN;

    @Schema(description = "图形验证码键，来自 /api/captcha/image；开启闸门时必填")
    private String captchaKey;

    @Schema(description = "图形验证码答案；开启闸门时必填，校验通过后才发送短信")
    private String captchaCode;
}
