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
 * 群成员列表项。
 *
 * <p>资料字段（账号 / 昵称 / 头像 / 在线状态）由 {@code UserQuerySpi} 批量补齐，
 * 关系字段（角色 / 群昵称 / 禁言 / 入群时间）来自本模块的 {@code im_group_member} 行，
 * 两部分都是批量查询后在内存里拼装，成员列表再长也只有两次 IO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群成员信息")
public class GroupMemberVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "成员用户 ID")
    private Long userId;

    @Schema(description = "成员账号")
    private String username;

    @Schema(description = "成员昵称")
    private String nickname;

    @Schema(description = "展示名：群内昵称 > 账号昵称 > 账号 > 兜底文案")
    private String displayName;

    @Schema(description = "成员头像")
    private String avatar;

    @Schema(description = "是否在线")
    private Boolean online;

    @Schema(description = "群内角色：1 群主 2 管理员 3 成员")
    private Integer role;

    @Schema(description = "角色文案")
    private String roleDesc;

    @Schema(description = "群内昵称，为空表示沿用账号昵称")
    private String nicknameInGroup;

    @Schema(description = "当前是否被禁言，已含到期时间的惰性判定")
    private Boolean muted;

    @Schema(description = "禁言到期时间，为空表示无限期或未禁言")
    private LocalDateTime muteEndTime;

    @Schema(description = "最近一次入群时间")
    private LocalDateTime joinTime;
}
