package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个群的后台档案（{@code im_group}），由 im-group 经 {@code AdminGroupSpi} 提供。
 *
 * <p>列表行与档案共用本类：{@code members} / {@code joinRequests} / {@code robots} /
 * {@code pendingJoinCount} 只在档案里有值，列表行为 null（成员页签另有
 * {@code pageMembers} 走真分页，档案里带的那份只是「最近的若干成员」的快照，用于一眼看清规模）。
 *
 * <p><b>绝不含 {@code invite_code}</b>：它是入群凭证，拿到就能把人塞进任意一个开了邀请链接的群，
 * 与远程控制那一屏不暴露 accessCode / ticket 是同一条红线。后台确实有「查这个群的邀请码」这种
 * 合理需求（帮群主找回链接），但那条路应该单独设点、单独留痕，不搭这一屏的顺风车。
 *
 * <p>机器人那一段<b>绝不含 {@code webhook_token}</b>：同理，那是外部系统代发群消息的凭证。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminGroupDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 群 ID */
    private Long groupId;

    /** 群名称 */
    private String name;

    /** 群头像 */
    private String avatar;

    /** 群公告 */
    private String notice;

    /** 群主用户 ID */
    private Long ownerId;

    /** 群主昵称（查不到给「已注销用户」；群主被注销而群还活着是真实存在的情况） */
    private String ownerName;

    /** 当前在群成员数（{@code status=1} 的行数，现算不落列） */
    private Long memberCount;

    /** 人数上限 */
    private Integer maxMember;

    /** 全员禁言：1 是 0 否 */
    private Integer muteAll;

    /** 入群是否需要审批：1 需要 0 不需要 */
    private Integer joinApproval;

    /** 状态：1 正常 0 已解散 */
    private Integer status;

    /** 建群时间 */
    private LocalDateTime createTime;

    /** 最后改动时间 */
    private LocalDateTime updateTime;

    /** 待审批入群申请数（档案里有值；没开审批的群恒为 0） */
    private Long pendingJoinCount;

    /** 档案里的成员快照，按入群时间倒序、条数由实现方夹死；列表行为 null */
    private List<MemberItem> members;

    /** 档案里的入群申请（含已处理的），按提交时间倒序、条数夹死；列表行为 null */
    private List<JoinRequestItem> joinRequests;

    /** 档案里的群机器人（含已停用）；列表行为 null */
    private List<RobotItem> robots;

    /** 群成员一行 */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 成员用户 ID */
        private Long userId;

        /** 昵称（用户表当前值） */
        private String nickname;

        /** 群内昵称，可为空 */
        private String nicknameInGroup;

        /** 群内角色：1 群主 2 管理员 3 普通成员 */
        private Integer role;

        /** 是否被单独禁言：1 是 0 否 */
        private Integer muted;

        /** 禁言结束时间，为空表示无限期 */
        private LocalDateTime muteEndTime;

        /** 入群时间 */
        private LocalDateTime joinTime;

        /** 在群状态：1 在群 0 已退群/被移除（档案的成员页默认只给 1） */
        private Integer status;
    }

    /** 一条入群申请 */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JoinRequestItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 申请 ID */
        private Long requestId;

        /** 申请人 ID */
        private Long userId;

        /** 申请人昵称 */
        private String nickname;

        /** 状态：0 待审批 1 已通过 2 已拒绝 */
        private Integer status;

        /** 申请留言 */
        private String message;

        /** 来源：link / qrcode */
        private String source;

        /** 处理人 ID（群主/管理员，不是后台管理员） */
        private Long handleUserId;

        /** 处理时间 */
        private LocalDateTime handleTime;

        /** 提交时间 */
        private LocalDateTime createTime;
    }

    /** 一个群机器人 */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RobotItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 机器人 ID */
        private Long robotId;

        /** 展示名 */
        private String name;

        /** 头像 */
        private String avatar;

        /** 用途说明 */
        private String description;

        /** 状态：1 启用 0 停用 */
        private Integer status;

        /** 创建者 ID */
        private Long creatorId;

        /** 创建者昵称 */
        private String creatorName;

        /** 创建时间 */
        private LocalDateTime createTime;
    }
}
