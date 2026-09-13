package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 他人资料卡片，只暴露公开信息，不含手机号与邮箱。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户资料卡片")
public class UserCardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "登录账号")
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

    @Schema(description = "注册时间")
    private LocalDateTime createTime;

    @Schema(description = "与当前登录用户是否已是好友")
    private Boolean friend;

    @Schema(description = "当前登录用户给对方的备注名")
    private String remark;

    @Schema(description = "当前登录用户是否已拉黑对方")
    private Boolean blocked;

    @Schema(description = "对方是否已拉黑当前登录用户，为 true 时无法发消息")
    private Boolean blockedByOther;
}
