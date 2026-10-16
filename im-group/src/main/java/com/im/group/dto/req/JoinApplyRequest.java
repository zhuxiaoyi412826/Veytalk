package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 通过邀请码申请入群入参。
 *
 * <p>前端拿到邀请链接或扫描群二维码后，把 {@code code} 传回后端；
 * 后端根据目标群的 {@code join_approval} 决定直接入群还是写入待审批。
 */
@Data
@Schema(description = "邀请码申请入群入参")
public class JoinApplyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群邀请码（链接或二维码解码得到）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "邀请码不能为空")
    @Size(max = 32, message = "邀请码长度不能超过 32")
    private String code;

    @Schema(description = "来源：link / qrcode，默认 link", example = "link")
    @Size(max = 32, message = "来源长度不能超过 32")
    private String source;

    @Schema(description = "申请留言，仅当目标群开启了入群审批才有意义")
    @Size(max = 255, message = "申请留言长度不能超过 255")
    private String message;
}
