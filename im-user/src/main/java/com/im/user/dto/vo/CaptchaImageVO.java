package com.im.user.dto.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 图形验证码响应，{@code image} 为可直接赋给 {@code <img src>} 的 Data URI。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "图形验证码")
public class CaptchaImageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "验证码键，登录/注册时随请求一起提交")
    private String captchaKey;

    @Schema(description = "验证码图片，Data URI 形式的 Base64 PNG")
    private String image;

    @Schema(description = "有效期（秒）", example = "300")
    private long expiresIn;

    @Schema(description = "开发环境回显的验证码答案，生产环境为 null")
    private String debugCode;
}
