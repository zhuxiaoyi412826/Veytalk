package com.im.group.service;

import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.group.entity.Group;
import com.im.group.entity.GroupMember;
import com.im.group.mapper.GroupMapper;
import com.im.group.mapper.GroupMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 群权限校验器。
 *
 * <p>把「群是否存在 → 我在不在群里 → 我够不够格操作」这条链路收在一处：群组接口里几乎每个
 * 方法都要走一遍，如果散落在 Service 各方法里，漏写一个 {@code status = 1} 或漏判一次角色，
 * 就会变成越权漏洞。校验失败统一抛 6xxx 业务异常，由全局异常处理器转成友好响应。
 *
 * <p>每个 {@code requireXxx} 都返回 {@link GroupContext}，把查到的群与成员行一起带出去，
 * 调用方不必为了拿群名或成员角色再查一次库。
 */
@Component
@RequiredArgsConstructor
public class GroupPermissionChecker {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;

    /**
     * 一次权限校验的结果，同时带回群与操作者的成员行。
     *
     * @param group  群实体，必然存在且未解散
     * @param member 操作者的在群成员行，必然非空
     */
    public record GroupContext(Group group, GroupMember member) {

        public Long groupId() {
            return group.getId();
        }

        public boolean owner() {
            return member.isOwner();
        }

        public boolean manager() {
            return member.isManager();
        }
    }

    /**
     * 校验群存在且未解散。
     *
     * <p>这里刻意不用 {@code selectNormalById}：它把「不存在」与「已解散」都压成 null，
     * 而这两种情况给用户的提示完全不同，混在一起会让人对着一个已经解散的群反复重试。
     */
    public Group requireGroup(Long groupId) {
        BusinessException.throwIf(groupId == null, ResultCode.BAD_REQUEST, "群 ID 不能为空");
        Group group = groupMapper.selectById(groupId);
        BusinessException.throwIf(group == null, ResultCode.GROUP_NOT_FOUND);
        BusinessException.throwIf(group.isDismissed(), ResultCode.GROUP_DISMISSED);
        return group;
    }

    /**
     * 校验用户是群成员。
     */
    public GroupContext requireMember(Long groupId, Long userId) {
        return requireMember(requireGroup(groupId), userId);
    }

    /**
     * 校验用户是群成员，群实体由调用方传入以复用已查到的行。
     */
    public GroupContext requireMember(Group group, Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.UNAUTHORIZED);
        GroupMember member = groupMemberMapper.selectActive(group.getId(), userId);
        BusinessException.throwIf(member == null, ResultCode.GROUP_NOT_MEMBER);
        return new GroupContext(group, member);
    }

    /**
     * 校验用户是群主或管理员：改群资料、邀人、移人、禁言等日常群管理动作的门槛。
     */
    public GroupContext requireOwnerOrAdmin(Long groupId, Long userId) {
        GroupContext context = requireMember(groupId, userId);
        BusinessException.throwUnless(context.manager(), ResultCode.GROUP_PERMISSION_DENIED);
        return context;
    }

    /**
     * 校验用户是群主：解散群、转让群主、任免管理员这类不可逆或影响权力结构的操作。
     */
    public GroupContext requireOwner(Long groupId, Long userId) {
        GroupContext context = requireMember(groupId, userId);
        BusinessException.throwUnless(context.owner(), ResultCode.GROUP_PERMISSION_DENIED);
        return context;
    }

    /**
     * 查询目标成员的在群行，不在群内直接抛 6002。
     *
     * <p>与 {@link #requireMember(Long, Long)} 的区别只在于语义：这里的目标是被操作者而非调用者，
     * 单独一个方法名能让 Service 里的调用点读起来是「对谁做了什么」。
     */
    public GroupMember requireTargetMember(Long groupId, Long targetUserId) {
        BusinessException.throwIf(targetUserId == null, ResultCode.BAD_REQUEST, "目标用户不能为空");
        GroupMember target = groupMemberMapper.selectActive(groupId, targetUserId);
        BusinessException.throwIf(target == null, ResultCode.GROUP_NOT_MEMBER);
        return target;
    }

    /**
     * 判断操作者能否对目标成员行使管理动作（移除 / 禁言 / 改角色）。
     *
     * <p>层级规则：群主可管理除自己以外的所有人，管理员只能管理普通成员，普通成员谁都管不了。
     * 管理员之间互不管辖——否则两个管理员可以互相移除对方，群管理会陷入循环报复。
     */
    public boolean canManage(GroupMember actor, GroupMember target) {
        if (actor == null || target == null) {
            return false;
        }
        if (actor.getUserId().equals(target.getUserId())) {
            return false;
        }
        if (actor.isOwner()) {
            return true;
        }
        return actor.isManager() && !target.isManager();
    }

    /**
     * {@link #canManage} 的断言版本，不满足时抛 6004。
     */
    public void requireManageable(GroupMember actor, GroupMember target) {
        BusinessException.throwUnless(canManage(actor, target), ResultCode.GROUP_PERMISSION_DENIED);
    }
}
