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
 * 群组详情 / 我的群列表项。
 *
 * <p>{@code myXxx} 系列字段是「查看者视角」的：同一个群，群主和普通成员看到的
 * {@code myRole}、{@code myMuted} 不同，因此这个 VO 不能缓存到跨用户共享的地方。
 *
 * <p>{@code conversationId} 由 {@code ConversationSpi} 回填。会话模块没有「按群 ID 反查会话」
 * 的对外接口，前端只能从这里拿到群会话 ID 后直接跳聊天窗口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群组信息")
public class GroupVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群 ID")
    private Long groupId;

    @Schema(description = "群名称")
    private String name;

    @Schema(description = "群头像")
    private String avatar;

    @Schema(description = "群公告")
    private String notice;

    @Schema(description = "群主用户 ID")
    private Long ownerId;

    @Schema(description = "群主昵称")
    private String ownerNickname;

    @Schema(description = "群主头像")
    private String ownerAvatar;

    @Schema(description = "成员数上限")
    private Integer maxMember;

    @Schema(description = "当前成员数")
    private Integer memberCount;

    @Schema(description = "是否全员禁言")
    private Boolean muteAll;

    @Schema(description = "我在群内的角色：1 群主 2 管理员 3 成员；非成员为 null")
    private Integer myRole;

    @Schema(description = "我的角色文案")
    private String myRoleDesc;

    @Schema(description = "我在群内的昵称，为空表示沿用账号昵称")
    private String myNickname;

    @Schema(description = "我当前是否被禁言，含全员禁言的判定")
    private Boolean myMuted;

    @Schema(description = "我是否具备群管理权限（群主或管理员）")
    private Boolean myManager;

    @Schema(description = "我是否是该群成员")
    private Boolean joined;

    @Schema(description = "群聊会话 ID，用于前端直接跳转聊天窗口")
    private Long conversationId;

    @Schema(description = "建群时间")
    private LocalDateTime createTime;
}
