package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录成功响应，前端需保存 {@code token} 并在后续请求头中携带 {@code tokenName} 字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录结果")
public class LoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "登录凭证，请求头键名为 tokenName")
    private String token;

    @Schema(description = "凭证所在的请求头名称，默认 satoken", example = "satoken")
    private String tokenName;

    @Schema(description = "凭证剩余有效期（秒）")
    private long expiresIn;

    @Schema(description = "本次登录的设备标识", example = "web")
    private String deviceType;

    @Schema(description = "登录用户资料")
    private UserVO userInfo;
}
