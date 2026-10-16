package com.im.group.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 入群申请列表项（管理员审批视图）。
 *
 * <p>资料字段（昵称、头像）由 {@code UserQuerySpi} 批量补齐，状态与留言来自本模块的
 * {@code im_group_join_request} 行；与 {@code GroupMemberVO} 的组装思路一致，一次分页只有两次 IO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "入群申请信息")
public class JoinRequestVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "申请 ID")
    private Long requestId;

    @Schema(description = "目标群 ID")
    private Long groupId;

    @Schema(description = "申请人用户 ID")
    private Long userId;

    @Schema(description = "申请人账号")
    private String username;

    @Schema(description = "申请人昵称")
    private String nickname;

    @Schema(description = "申请人头像")
    private String avatar;

    @Schema(description = "申请留言")
    private String message;

    @Schema(description = "来源：link / qrcode")
    private String source;

    @Schema(description = "状态：0 待审批 1 已通过 2 已拒绝")
    private Integer status;

    @Schema(description = "状态文案")
    private String statusDesc;

    @Schema(description = "提交时间")
    private LocalDateTime createTime;

    @Schema(description = "处理时间，未处理为空")
    private LocalDateTime handleTime;

    @Schema(description = "处理人用户 ID")
    private Long handleUserId;

    @Schema(description = "处理人展示名")
    private String handleUserName;
}
