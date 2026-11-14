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

    /**
     * 这个账号还有必签 / 可选协议没对当前生效版表过态，前端据此挂再同意弹窗。
     *
     * <p>放在登录返回体里而不是让每个客户端登录后都去调一次 {@code /api/agreement/pending}：
     * 前者是「登录这件事本来就要算的东西」，后者是「为了拿一个布尔位多开一枪」。
     * 明细（哪几份、哪几版、能不能跳过）仍然由 pending 接口给，这里只是一声提示。
     */
    @Schema(description = "是否有待同意的协议，true 时前端应拉取 /api/agreement/pending 并挂弹窗")
    private boolean agreementPending;
}
