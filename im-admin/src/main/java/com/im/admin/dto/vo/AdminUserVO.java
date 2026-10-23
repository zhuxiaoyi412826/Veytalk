package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台用户列表的一行：im-user 给的档案 + im-admin 自己算出的「被举报次数」。
 *
 * <p>为什么不直接把 {@code AdminUserDTO} 当返回值：被举报次数来自 {@code im_report}，
 * 那是后台的表，用户模块不该知道它存在。两者在这里拼一次，
 * 拼的动作放在 service 里（批量取，不逐行查）。
 */
@Data
@Schema(description = "后台用户行")
public class AdminUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "登录账号")
    private String account;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "脱敏手机号")
    private String phone;

    @Schema(description = "账号状态：1 正常 0 已禁用")
    private Integer status;

    @Schema(description = "是否在线")
    private Boolean online;

    @Schema(description = "积分余额")
    private Long balance;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;

    @Schema(description = "是否管理员账号")
    private Boolean admin;

    @Schema(description = "当前未处置的被举报次数（含待处理与已受理）")
    private Long pendingReportCount;
}
