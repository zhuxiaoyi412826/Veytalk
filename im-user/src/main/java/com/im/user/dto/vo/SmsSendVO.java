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
 * 短信验证码发送结果。
 *
 * <p>本项目不接真实短信服务商，验证码写入 Redis 与日志；
 * 仅当 {@code im.captcha.expose-sms-code=true}（开发环境默认）时才在 {@code debugCode} 中回显，便于联调。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "短信验证码发送结果")
public class SmsSendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "接收手机号，中间四位已脱敏", example = "138****0001")
    private String phone;

    @Schema(description = "验证码有效期（秒）", example = "300")
    private long expiresIn;

    @Schema(description = "距离下次可发送的剩余秒数", example = "60")
    private long retryAfter;

    @Schema(description = "开发环境回显的验证码，生产环境为 null")
    private String debugCode;
}
