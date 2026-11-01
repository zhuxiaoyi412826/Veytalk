package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 扫码登录中「APP 侧」携带场景号的请求体：扫码告知与授权确认都用它。
 *
 * <p>{@code scene} 即二维码里编的那个随机串，是网页与后端之间约定的一次性会话标识；
 * 授权接口不从报文里取 userId，而是取当前 APP 的登录用户，避免越权替他人授权。
 */
@Data
@Schema(description = "扫码登录场景号请求")
public class QrSceneRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "场景号，来自二维码内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "场景号不能为空")
    private String scene;
}
