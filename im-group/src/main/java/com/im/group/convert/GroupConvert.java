package com.im.group.convert;

import com.im.common.domain.GroupBriefDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.GroupRole;
import com.im.common.util.TextUtil;
import com.im.group.dto.vo.GroupMemberVO;
import com.im.group.dto.vo.GroupVO;
import com.im.group.entity.Group;
import com.im.group.entity.GroupMember;

/**
 * 群组实体到视图对象的转换。
 *
 * <p>与 {@code FriendConvert} 一样保持无状态且不注入任何 SPI：群主资料、成员资料、在线状态
 * 都由 Service 批量查好后作为参数传入，一次列表渲染只有常数次 IO，也不会产生 N+1。
 *
 * <p>禁言的综合判定 {@link #mutedNow} 放在这里而不是 Service，是因为 SPI 实现、详情组装、
 * 成员列表三处都要用同一套规则，散在各处迟早会出现「详情说没禁言、发消息却被拦下」的不一致。
 */
public final class GroupConvert {

    private GroupConvert() {
    }

    /**
     * 组装群详情。
     *
     * @param group          群实体，不可为空
     * @param myMember       查看者的成员行，非成员传 {@code null}
     * @param owner          群主精简资料，账号已注销时为 {@code null}
     * @param memberCount    当前在群成员数
     * @param conversationId 群聊会话 ID，会话模块不可用时为 {@code null}
     */
    public static GroupVO toVO(Group group, GroupMember myMember, UserBriefDTO owner,
                              int memberCount, Long conversationId) {
        boolean joined = myMember != null && myMember.isInGroup();
        GroupRole myRole = joined ? GroupRole.of(myMember.getRole()) : null;
        return GroupVO.builder()
                .groupId(group.getId())
                .name(group.getName())
                .avatar(group.getAvatar())
                .notice(group.getNotice())
                .ownerId(group.getOwnerId())
                .ownerNickname(owner == null ? null : owner.getNickname())
                .ownerAvatar(owner == null ? null : owner.getAvatar())
                .maxMember(group.getMaxMember())
                .memberCount(memberCount)
                .muteAll(group.isMuteAllOn())
                .myRole(myRole == null ? null : myRole.getCode())
                .myRoleDesc(myRole == null ? null : myRole.getDesc())
                .myNickname(joined ? myMember.getNicknameInGroup() : null)
                .myMuted(mutedNow(group, myMember))
                .myManager(myRole != null && myRole.isManager())
                .joined(joined)
                .conversationId(conversationId)
                .createTime(group.getCreateTime())
                .build();
    }

    /**
     * 组装群成员列表项。
     *
     * @param member 成员行，必须是在群状态
     * @param user   成员精简资料，账号已注销时为 {@code null}
     * @param online 是否在线
     */
    public static GroupMemberVO toMemberVO(GroupMember member, UserBriefDTO user, boolean online) {
        GroupRole role = GroupRole.of(member.getRole());
        boolean muted = member.isMutedNow();
        return GroupMemberVO.builder()
                .userId(member.getUserId())
                .username(user == null ? null : user.getUsername())
                .nickname(user == null ? null : user.getNickname())
                .displayName(displayName(member.getNicknameInGroup(), user, member.getUserId()))
                .avatar(user == null ? null : user.getAvatar())
                .online(online)
                .role(role.getCode())
                .roleDesc(role.getDesc())
                .nicknameInGroup(member.getNicknameInGroup())
                .muted(muted)
                // 禁言已失效时不回传到期时间，否则前端会把一个过去的时间点当成「仍在禁言」渲染
                .muteEndTime(muted ? member.getMuteEndTime() : null)
                .joinTime(member.getJoinTime())
                .build();
    }

    /**
     * 组装跨模块传输用的群精简信息。
     *
     * @param myMember    查看者的成员行，用于回填 myRole / myNickname，可为 {@code null}
     * @param memberCount 在群成员数，批量场景下取不到时传 {@code null}
     */
    public static GroupBriefDTO toBriefDTO(Group group, GroupMember myMember, Integer memberCount) {
        boolean joined = myMember != null && myMember.isInGroup();
        return GroupBriefDTO.builder()
                .groupId(group.getId())
                .name(group.getName())
                .avatar(group.getAvatar())
                .notice(group.getNotice())
                .ownerId(group.getOwnerId())
                .memberCount(memberCount)
                .myRole(joined ? myMember.getRole() : null)
                .myNickname(joined ? myMember.getNicknameInGroup() : null)
                .build();
    }

    /**
     * 综合判定成员当前是否被禁言。
     *
     * <p>两条禁言来源取「或」：单人禁言未到期，或全员禁言开启且自己不是管理层。
     * 群主与管理员对全员禁言免疫，否则群主在开启全员禁言后连一句解释都发不出去，
     * 会陷入「只能禁言、无法沟通」的死局。
     *
     * @return 成员为空或已退群时返回 {@code false}
     */
    public static boolean mutedNow(Group group, GroupMember member) {
        if (member == null || !member.isInGroup()) {
            return false;
        }
        if (member.isMutedNow()) {
            return true;
        }
        return group != null && group.isMuteAllOn() && !member.isManager();
    }

    /**
     * 展示名优先级：群内昵称 &gt; 账号昵称 &gt; 账号 &gt; 兜底文案。
     *
     * <p>账号被注销时 {@code user} 为 null，仍需给出可读名称，避免成员列表出现空白行。
     */
    public static String displayName(String nicknameInGroup, UserBriefDTO user, Long userId) {
        if (TextUtil.isNotBlank(nicknameInGroup)) {
            return nicknameInGroup;
        }
        if (user != null && TextUtil.isNotBlank(user.getNickname())) {
            return user.getNickname();
        }
        if (user != null && TextUtil.isNotBlank(user.getUsername())) {
            return user.getUsername();
        }
        return "用户" + userId;
    }
}
