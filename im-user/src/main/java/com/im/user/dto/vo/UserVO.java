package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前登录用户的完整资料，不含密码等敏感字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户资料")
public class UserVO implements Serializable {

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

    @Schema(description = "手机号，仅本人资料接口返回，他人资料卡片不携带")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "状态：1 正常 0 禁用")
    private Integer status;

    @Schema(description = "是否已设置密码；验证码登录自动建号的账号为 false，需引导首次设置")
    private Boolean passwordSet;

    @Schema(description = "是否在线")
    private Boolean online;

    @Schema(description = "最近登录时间")
    private LocalDateTime lastLoginTime;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;

    @Schema(description = "角色编码集合")
    private List<String> roles;

    @Schema(description = "权限码集合")
    private List<String> permissions;
}
