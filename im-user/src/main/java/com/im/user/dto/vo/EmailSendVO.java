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
 * 邮箱验证码发送结果。
 *
 * <p>验证码通过 QQ 邮箱 SMTP 发送；仅当 {@code im.captcha.expose-email-code=true}（开发环境）时
 * 才在 {@code debugCode} 中回显，便于未接真实收件箱时联调。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "邮箱验证码发送结果")
public class EmailSendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "接收邮箱，已做脱敏", example = "us***@qq.com")
    private String email;

    @Schema(description = "验证码有效期（秒）", example = "300")
    private long expiresIn;

    @Schema(description = "距离下次可发送的剩余秒数", example = "60")
    private long retryAfter;

    @Schema(description = "开发环境回显的验证码，生产环境为 null")
    private String debugCode;
}
