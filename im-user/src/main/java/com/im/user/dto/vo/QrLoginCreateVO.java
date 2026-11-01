package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 扫码登录二维码生成结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "扫码登录二维码")
public class QrLoginCreateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "场景号，网页轮询状态与 APP 授权都凭它")
    private String scene;

    @Schema(description = "PNG 二维码的 base64 数据 URL，可直接放进 <img src>")
    private String qrcode;

    @Schema(description = "二维码有效期（秒）", example = "120")
    private long expiresIn;
}
