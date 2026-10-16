package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账号注销请求：以登录密码做二次确认，防止会话被劫持后误销账号。
 */
@Data
@Schema(description = "账号注销请求")
public class CancelAccountRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "请输入登录密码以确认注销")
    @Schema(description = "当前登录密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
