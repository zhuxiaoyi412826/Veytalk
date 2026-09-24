package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户精简信息，跨模块传输使用，不含任何敏感字段。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户精简信息")
public class UserBriefDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "账号")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像访问地址")
    private String avatar;

    @Schema(description = "性别：0 未知 1 男 2 女")
    private Integer gender;

    @Schema(description = "个性签名")
    private String signature;

    @Schema(description = "是否在线")
    private Boolean online;
}
