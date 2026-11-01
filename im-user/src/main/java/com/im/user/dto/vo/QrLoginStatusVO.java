package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 扫码登录状态轮询结果。
 *
 * <p>{@code status}：0 待扫码、1 已扫码待确认、2 已授权（此时下发一次性登录包）、3 已失效/过期。
 * 只有 status=2 时 {@code token}/{@code tokenName} 有值，且后端随即销毁该 scene，
 * 一个二维码只能换一次登录态，防止被重复轮询重放。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "扫码登录状态")
public class QrLoginStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_SCANNED = 1;
    public static final int STATUS_AUTHORIZED = 2;
    public static final int STATUS_EXPIRED = 3;

    @Schema(description = "状态：0 待扫码 / 1 已扫码待确认 / 2 已授权 / 3 已失效")
    private int status;

    @Schema(description = "仅 status=2 时返回：web 设备登录凭证")
    private String token;

    @Schema(description = "仅 status=2 时返回：凭证所在请求头名，默认 satoken")
    private String tokenName;

    @Schema(description = "凭证有效期（秒）")
    private long expiresIn;
}
