package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 生成扫码登录二维码的入参。
 *
 * <p>{@code base} 是网页端自己的站点根地址（origin），后端只取 {@code scheme://host[:port]}
 * 拼出二维码里的跳转链接，防止把任意字符串编进二维码造成开放重定向。
 */
@Data
@Schema(description = "扫码登录二维码生成请求")
public class QrCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "网页站点根地址（origin），用于拼二维码里的扫码回跳链接", example = "http://localhost:5173")
    private String base;
}
