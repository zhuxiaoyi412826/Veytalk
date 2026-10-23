package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 解锁一间密码直播。
 *
 * <p>只有密码房走这里：付费房的解锁是 {@code POST /api/live/{roomId}/pay}（扣款即解锁，不需要请求体）。
 * 密码经 SHA-256 摘要后与开播时存的那份比对，明文只在 HTTPS 传输里过一遍、绝不落 Redis。
 */
@Data
@Schema(description = "解锁密码直播")
public class LiveUnlockRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "房间密码", example = "1234")
    @NotBlank(message = "密码不能为空")
    @Size(max = 32, message = "密码最多 32 位")
    private String password;
}
