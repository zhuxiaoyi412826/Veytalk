package com.im.friend.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 好友申请列表项。
 *
 * <p>收到的与我发出的申请共用此结构，前端靠 {@code direction} 区分渲染：
 * {@code received} 显示「同意 / 拒绝」按钮，{@code sent} 只显示处理状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "好友申请信息")
public class FriendRequestVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 列表视角：我收到的申请 */
    public static final String DIRECTION_RECEIVED = "received";
    /** 列表视角：我发出的申请 */
    public static final String DIRECTION_SENT = "sent";

    @Schema(description = "申请 ID")
    private Long id;

    @Schema(description = "申请人 ID")
    private Long fromUserId;

    @Schema(description = "被申请人 ID")
    private Long toUserId;

    @Schema(description = "列表视角：received 我收到的 / sent 我发出的")
    private String direction;

    @Schema(description = "对方用户 ID，received 时为申请人，sent 时为被申请人")
    private Long peerUserId;

    @Schema(description = "对方账号")
    private String peerUsername;

    @Schema(description = "对方昵称")
    private String peerNickname;

    @Schema(description = "对方头像")
    private String peerAvatar;

    @Schema(description = "对方是否在线")
    private Boolean peerOnline;

    @Schema(description = "验证消息")
    private String verifyMessage;

    @Schema(description = "申请来源：search / qrcode / group")
    private String source;

    @Schema(description = "状态：0 待处理 1 已同意 2 已拒绝 3 已过期")
    private Integer status;

    @Schema(description = "状态中文描述")
    private String statusDesc;

    @Schema(description = "是否仍可由我处理（仅 received 且待处理时为 true）")
    private Boolean actionable;

    @Schema(description = "处理时间")
    private LocalDateTime handleTime;

    @Schema(description = "申请时间")
    private LocalDateTime createTime;
}
