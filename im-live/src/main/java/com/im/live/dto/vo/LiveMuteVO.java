package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 主播「禁言名单」面板的一行。
 *
 * <p>直接由 Redis 里的 {@code LiveMutePO} 快照翻译而来，不回查用户表：
 * 面板要的是「我什么时候把谁禁了」，昵称头像用当时那份即可。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "被禁言用户")
public class LiveMuteVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "被禁时的昵称")
    private String nickname;

    @Schema(description = "被禁时的头像")
    private String avatar;

    @Schema(description = "解禁时间戳（毫秒），永久禁言为 0")
    private Long expireAt;

    @Schema(description = "是否永久禁言")
    private Boolean permanent;

    @Schema(description = "禁言时刻（毫秒）")
    private Long mutedAt;
}
