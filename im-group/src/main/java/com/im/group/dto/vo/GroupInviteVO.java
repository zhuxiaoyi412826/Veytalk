package com.im.group.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 群邀请信息：邀请码、可复制的邀请链接、以及二维码图片。
 *
 * <p>一次 {@code GET /api/group/{id}/invite} 把三样都带回，前端「邀请入群」面板不用发两次请求。
 * {@code qrcode} 是后端 zxing 生成的 PNG data URL（{@code data:image/png;base64,...}），直接塞 {@code <img>} 即可，
 * 前端无需再引二维码库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群邀请信息（邀请码 / 链接 / 二维码）")
public class GroupInviteVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群邀请码")
    private String inviteCode;

    @Schema(description = "完整邀请链接，扫码或点击后进入入群申请页")
    private String inviteLink;

    @Schema(description = "邀请链接的二维码 PNG（base64 data URL）")
    private String qrcode;
}
