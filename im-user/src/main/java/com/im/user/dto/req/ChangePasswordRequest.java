package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改密码入参。
 */
@Data
@Schema(description = "修改密码请求")
public class ChangePasswordRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "原密码；验证码登录自动建号、从未设过密码的账号首次设置时可留空")
    private String oldPassword;

    @Schema(description = "新密码，6-32 位", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "新密码长度需在 6-32 位之间")
    private String newPassword;

    @Schema(description = "修改成功后是否踢掉全部已登录终端，默认 true")
    private Boolean logoutAll = Boolean.TRUE;
}
