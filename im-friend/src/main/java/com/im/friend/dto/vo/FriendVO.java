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
 * 好友列表项。
 *
 * <p>用户资料（昵称 / 头像 / 在线状态）由 {@code UserQuerySpi} 批量补齐，
 * 关系字段（备注 / 分组 / 拉黑）来自本模块的 {@code im_friend} 行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "好友信息")
public class FriendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "好友用户 ID")
    private Long friendId;

    @Schema(description = "好友账号")
    private String username;

    @Schema(description = "好友昵称")
    private String nickname;

    @Schema(description = "展示名：有备注时取备注，否则取昵称")
    private String displayName;

    @Schema(description = "好友头像")
    private String avatar;

    @Schema(description = "性别：0 未知 1 男 2 女")
    private Integer gender;

    @Schema(description = "个性签名")
    private String signature;

    @Schema(description = "是否在线")
    private Boolean online;

    @Schema(description = "我对好友的备注名")
    private String remark;

    @Schema(description = "好友分组名")
    private String groupName;

    @Schema(description = "关系状态：1 正常 2 我已拉黑对方")
    private Integer status;

    @Schema(description = "是否已被我拉黑")
    private Boolean blocked;

    @Schema(description = "好友关系建立时间")
    private LocalDateTime createTime;
}
