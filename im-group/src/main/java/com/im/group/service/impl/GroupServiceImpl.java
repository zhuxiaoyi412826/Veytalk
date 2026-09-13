package com.im.group.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.PageQuery;
import com.im.common.domain.UserBriefDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.ConvType;
import com.im.common.enums.GroupRole;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.PushSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.group.convert.GroupConvert;
import com.im.group.dto.po.GroupMemberCount;
import com.im.group.dto.req.CreateGroupRequest;
import com.im.group.dto.req.UpdateGroupRequest;
import com.im.group.dto.vo.GroupMemberVO;
import com.im.group.dto.vo.GroupVO;
import com.im.group.entity.Group;
import com.im.group.entity.GroupMember;
import com.im.group.mapper.GroupMapper;
import com.im.group.mapper.GroupMemberMapper;
import com.im.group.service.GroupPermissionChecker;
import com.im.group.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 群组业务实现。
 *
 * <p>事务与副作用的边界是本类最重要的一条约定：
 * <ul>
 *   <li><b>事务内、失败即回滚</b>：群与成员表的全部写入，以及 {@code ConversationSpi} 的
 *       建会话 / 加人 / 移人。这些决定了「谁能在群里看到什么」，与群成员表必须同生共死，
 *       一旦分叉就会出现「已经在群里却收不到消息」这类极难排查的问题。</li>
 *   <li><b>事务提交后、失败只记日志</b>：系统通知与 WebSocket 推送。两者各自带事务，
 *       如果在本事务内抛异常又被 catch 吃掉，事务会被标记成 rollback-only，
 *       提交时抛 {@code UnexpectedRollbackException}——一条无关紧要的群通知就能让建群整体失败。</li>
 * </ul>
 *
 * <p>跨模块依赖一律走 {@code ObjectProvider}：群模块单独部署（或会话模块尚未装配）时不应启动失败，
 * 只有 {@code UserQuerySpi} 是直接注入的，因为成员列表连昵称都拼不出来时这个模块没有存在意义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    /** NOTIFY 报文的业务子类型，前端据此把群事件与好友申请事件分开处理 */
    private static final String BIZ_GROUP = "group";

    private static final String ACTION_CREATED = "created";
    private static final String ACTION_UPDATED = "updated";
    private static final String ACTION_MEMBERS_ADDED = "members-added";
    private static final String ACTION_MEMBER_REMOVED = "member-removed";
    private static final String ACTION_QUIT = "quit";
    private static final String ACTION_DISMISSED = "dismissed";
    private static final String ACTION_TRANSFERRED = "transferred";
    private static final String ACTION_MUTE_ALL = "mute-all";
    private static final String ACTION_MUTED = "muted";
    private static final String ACTION_UNMUTED = "unmuted";
    private static final String ACTION_ROLE_CHANGED = "role-changed";

    /** 单次邀请人数上限，与 {@code CreateGroupRequest} / {@code MemberIdsRequest} 的校验保持一致 */
    private static final int MAX_BATCH_INVITE = 100;

    /** 通知文案里最多列出几个人名，超出部分折叠成「等 N 人」 */
    private static final int MAX_NAMES_IN_NOTICE = 3;

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupPermissionChecker permissionChecker;
    private final UserQuerySpi userQuerySpi;
    private final ObjectProvider<ConversationSpi> conversationSpiProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;
    private final ObjectProvider<PushSpi> pushSpiProvider;

    /* ==================== 建群与群资料 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupVO create(Long operatorId, CreateGroupRequest request) {
        BusinessException.throwIf(operatorId == null, ResultCode.UNAUTHORIZED);
        BusinessException.throwIf(request == null, ResultCode.BAD_REQUEST, "建群参数不能为空");

        List<Long> invitees = distinctPositive(request.getMemberIds());
        BusinessException.throwIf(invitees.size() > MAX_BATCH_INVITE,
                ResultCode.BAD_REQUEST, "单次最多邀请 " + MAX_BATCH_INVITE + " 人");

        // 建群者放在集合首位，后面直接拿这个顺序当成员列表用，群主不必单独再查一次
        Set<Long> toLoad = new LinkedHashSet<>();
        toLoad.add(operatorId);
        toLoad.addAll(invitees);
        Map<Long, UserBriefDTO> users = loadUsers(toLoad);

        int maxMember = request.getMaxMember() == null
                ? ImConstants.DEFAULT_GROUP_MAX_MEMBER
                : request.getMaxMember();
        // 创建者自动入群，因此校验的是「1 + 邀请数」而不是邀请数
        BusinessException.throwIf(toLoad.size() > maxMember, ResultCode.GROUP_MEMBER_FULL);

        Group group = new Group();
        group.setName(TextUtil.sanitize(request.getName(), 64));
        group.setAvatar(TextUtil.isBlank(request.getAvatar()) ? null : request.getAvatar());
        group.setNotice(TextUtil.isBlank(request.getNotice()) ? null : TextUtil.sanitize(request.getNotice(), 512));
        group.setOwnerId(operatorId);
        group.setMaxMember(maxMember);
        group.setMuteAll(0);
        group.setStatus(Group.STATUS_NORMAL);
        groupMapper.insert(group);

        LocalDateTime now = LocalDateTime.now();
        List<Long> allMembers = new ArrayList<>(toLoad);
        for (Long userId : allMembers) {
            int role = userId.equals(operatorId) ? GroupRole.OWNER.getCode() : GroupRole.MEMBER.getCode();
            joinMember(group.getId(), userId, role, now);
        }

        Long conversationId = createGroupConversation(group.getId(), allMembers);
        GroupVO vo = GroupConvert.toVO(group, groupMemberMapper.selectActive(group.getId(), operatorId),
                users.get(operatorId), allMembers.size(), conversationId);

        Map<String, Object> event = groupEvent(ACTION_CREATED, group.getId(), conversationId, null);
        afterCommit(() -> {
            noticeQuietly(conversationId, name(users, operatorId) + " 创建了群聊");
            pushQuietlyAll(allMembers, event);
        });
        log.info("[建群] groupId={}, 群主={}, 成员数={}, conversationId={}",
                group.getId(), operatorId, allMembers.size(), conversationId);
        return vo;
    }

    /**
     * 群资料的三个字段都区分「没传」与「传空」，因此不能直接 {@code updateById}：
     * MyBatis-Plus 默认的字段策略会跳过 null 值，清空公告这种操作会被静默忽略。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupVO update(Long operatorId, Long groupId, UpdateGroupRequest request) {
        GroupPermissionChecker.GroupContext context = permissionChecker.requireOwnerOrAdmin(groupId, operatorId);
        BusinessException.throwIf(request == null, ResultCode.BAD_REQUEST, "修改内容不能为空");

        Group group = context.group();
        var wrapper = Wrappers.<Group>lambdaUpdate().eq(Group::getId, groupId);
        boolean changed = false;

        if (request.getName() != null) {
            String name = TextUtil.sanitize(request.getName(), 64);
            BusinessException.throwIf(TextUtil.isBlank(name), ResultCode.BAD_REQUEST, "群名称不能为空");
            wrapper.set(Group::getName, name);
            group.setName(name);
            changed = true;
        }
        if (request.getAvatar() != null) {
            if (TextUtil.isBlank(request.getAvatar())) {
                // 清空必须写成真正的 SQL NULL：set(field, null) 会生成一个 jdbcType 未知的空参数，
                // 部分驱动因无法推断类型而报错
                wrapper.setSql("avatar = NULL");
                group.setAvatar(null);
            } else {
                wrapper.set(Group::getAvatar, request.getAvatar());
                group.setAvatar(request.getAvatar());
            }
            changed = true;
        }
        if (request.getNotice() != null) {
            String notice = TextUtil.sanitize(request.getNotice(), 512);
            if (TextUtil.isBlank(notice)) {
                wrapper.setSql("notice = NULL");
                group.setNotice(null);
            } else {
                wrapper.set(Group::getNotice, notice);
                group.setNotice(notice);
            }
            changed = true;
        }

        Long conversationId = conversationIdOf(groupId);
        // 三个字段都没传时不执行 UPDATE：既省一次往返，也避免把 update_time 无意义地推后
        if (changed) {
            groupMapper.update(null, wrapper);
            // 改群资料不发群通知：改名改公告是高频操作，每次都刷一条系统消息会淹没正常聊天；
            // 只推 NOTIFY 让在线客户端刷新群头部即可
            afterCommit(() -> pushQuietlyAll(groupMemberMapper.selectActiveMemberIds(groupId),
                    groupEvent(ACTION_UPDATED, groupId, conversationId, null)));
        }
        return toVO(group, context.member(), conversationId);
    }

    @Override
    public GroupVO detail(Long viewerId, Long groupId) {
        Group group = permissionChecker.requireGroup(groupId);
        GroupMember myMember = viewerId == null ? null : groupMemberMapper.selectActive(groupId, viewerId);
        return toVO(group, myMember, conversationIdOf(groupId));
    }

    /**
     * 我的群列表。
     *
     * <p>三次批量查询拼完整个列表：群主资料、各群成员数、各群会话 ID，全部一次取回。
     * 逐群查会让一个加了 20 个群的用户在打开列表时打出上百条 SQL。
     */
    @Override
    public List<GroupVO> listMine(Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.UNAUTHORIZED);
        List<GroupMember> myRows = groupMemberMapper.selectActiveByUser(userId);
        if (myRows.isEmpty()) {
            return List.of();
        }
        List<Long> groupIds = myRows.stream().map(GroupMember::getGroupId).distinct().toList();
        // 只保留未解散的群：群解散时成员行会被一并置为已退群，但历史数据可能残留，这里再过滤一道
        Map<Long, Group> groups = groupMapper.selectNormalByIds(groupIds).stream()
                .collect(Collectors.toMap(Group::getId, Function.identity()));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> counts = memberCounts(groups.keySet());
        Map<Long, Long> conversationIds = groupConversationIds(userId);
        Set<Long> ownerIds = groups.values().stream()
                .map(Group::getOwnerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserBriefDTO> owners = ownerIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(ownerIds);

        List<GroupVO> result = new ArrayList<>(myRows.size());
        for (GroupMember row : myRows) {
            Group group = groups.get(row.getGroupId());
            if (group == null) {
                continue;
            }
            result.add(GroupConvert.toVO(group, row, owners.get(group.getOwnerId()),
                    counts.getOrDefault(group.getId(), 0), conversationIds.get(group.getId())));
        }
        return result;
    }

    /* ==================== 成员管理 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> addMembers(Long operatorId, Long groupId, Collection<Long> userIds) {
        GroupPermissionChecker.GroupContext context = permissionChecker.requireOwnerOrAdmin(groupId, operatorId);
        List<Long> candidates = distinctPositive(userIds);
        BusinessException.throwIf(candidates.isEmpty(), ResultCode.BAD_REQUEST, "成员列表不能为空");
        BusinessException.throwIf(candidates.size() > MAX_BATCH_INVITE,
                ResultCode.BAD_REQUEST, "单次最多邀请 " + MAX_BATCH_INVITE + " 人");

        Set<Long> toLoad = new LinkedHashSet<>(candidates);
        toLoad.add(operatorId);
        Map<Long, UserBriefDTO> users = loadUsers(toLoad);

        // 已在群里的人静默跳过：邀请列表通常是从好友列表整批勾选的，
        // 混进一个老成员不该让整批操作失败，返回值会告诉调用方实际加了谁
        Set<Long> existing = new HashSet<>(groupMemberMapper.selectActiveMemberIds(groupId));
        List<Long> toAdd = candidates.stream().filter(id -> !existing.contains(id)).toList();
        if (toAdd.isEmpty()) {
            return List.of();
        }
        Group group = context.group();
        int maxMember = group.getMaxMember() == null ? ImConstants.DEFAULT_GROUP_MAX_MEMBER : group.getMaxMember();
        BusinessException.throwIf(existing.size() + toAdd.size() > maxMember, ResultCode.GROUP_MEMBER_FULL);

        LocalDateTime now = LocalDateTime.now();
        List<Long> added = new ArrayList<>(toAdd.size());
        for (Long userId : toAdd) {
            if (joinMember(groupId, userId, GroupRole.MEMBER.getCode(), now)) {
                added.add(userId);
            }
        }
        if (added.isEmpty()) {
            return List.of();
        }

        Long conversationId = conversationIdOf(groupId);
        joinConversation(conversationId, added);

        Map<String, Object> event = groupEvent(ACTION_MEMBERS_ADDED, groupId, conversationId,
                Map.of("userIds", added));
        afterCommit(() -> {
            noticeQuietly(conversationId,
                    name(users, operatorId) + " 邀请 " + names(users, added) + " 加入了群聊");
            // 推给全体成员而不是只推新人：老成员的群成员数、@ 候选列表都变了
            pushQuietlyAll(groupMemberMapper.selectActiveMemberIds(groupId), event);
        });
        log.info("[群加人] groupId={}, 操作人={}, 新增={}, 跳过={}", groupId, operatorId, added.size(),
                candidates.size() - added.size());
        return added;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long operatorId, Long groupId, Long userId) {
        GroupPermissionChecker.GroupContext context = permissionChecker.requireOwnerOrAdmin(groupId, operatorId);
        GroupMember target = permissionChecker.requireTargetMember(groupId, userId);
        // canManage 已经排除了「对自己操作」：想退出请走退群接口，
        // 走移除会在群通知里显示成「被移出群聊」，语义不对
        permissionChecker.requireManageable(context.member(), target);
        leaveGroup(groupId, userId, operatorId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void quit(Long operatorId, Long groupId) {
        GroupPermissionChecker.GroupContext context = permissionChecker.requireMember(groupId, operatorId);
        // 群主直接退群会留下一个没人能改资料、也没人能解散的僵尸群，必须先转让
        BusinessException.throwIf(context.owner(), ResultCode.GROUP_OWNER_CANNOT_QUIT);
        leaveGroup(groupId, operatorId, operatorId, true);
    }

    /**
     * 退群与被移出的公共出口。
     *
     * @param actorId  触发者，主动退群时与 {@code userId} 相同
     * @param bySelf   {@code true} 表示主动退群，只影响通知文案
     */
    private void leaveGroup(Long groupId, Long userId, Long actorId, boolean bySelf) {
        // updateStatus 的条件里带 status = 1，重复退群只有第一次生效，之后直接当作成功返回
        if (groupMemberMapper.updateStatus(groupId, userId, GroupMember.STATUS_LEFT) == 0) {
            log.debug("[退群] 成员已不在群内，幂等跳过: groupId={}, userId={}", groupId, userId);
            return;
        }
        Long conversationId = conversationIdOf(groupId);
        leaveConversation(conversationId, userId);

        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(Set.of(userId, actorId));
        Map<String, Object> event = groupEvent(bySelf ? ACTION_QUIT : ACTION_MEMBER_REMOVED,
                groupId, conversationId, Map.of("userId", userId));
        afterCommit(() -> {
            noticeQuietly(conversationId, bySelf
                    ? name(users, userId) + " 退出了群聊"
                    : name(users, userId) + " 被 " + name(users, actorId) + " 移出群聊");
            // 离开的人也要收到通知，否则他的界面会一直停在一个已经发不出消息的群里
            pushQuietly(userId, event);
            pushQuietlyAll(groupMemberMapper.selectActiveMemberIds(groupId), event);
        });
        log.info("[退群] groupId={}, userId={}, 主动={}, conversationId={}", groupId, userId, bySelf, conversationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Long operatorId, Long groupId, Long userId, Integer role) {
        permissionChecker.requireOwner(groupId, operatorId);
        GroupMember target = permissionChecker.requireTargetMember(groupId, userId);
        GroupRole targetRole = GroupRole.of(role);
        // DTO 上已经限定 2/3，这里再判一次：Service 也会被 SPI 或内部逻辑直接调用，不能只依赖入口校验
        BusinessException.throwUnless(targetRole == GroupRole.ADMIN || targetRole == GroupRole.MEMBER,
                ResultCode.BAD_REQUEST, "只能设置为管理员或普通成员");
        BusinessException.throwIf(target.getUserId().equals(operatorId),
                ResultCode.BAD_REQUEST, "不能修改自己的角色");
        if (Objects.equals(target.getRole(), targetRole.getCode())) {
            return;
        }
        // updateRole 的条件里排除了当前角色为群主的行，所以群主那一行永远改不到，
        // 想换群主只能走 transfer；返回 0 说明目标在这一瞬间退群了
        BusinessException.throwIf(groupMemberMapper.updateRole(groupId, userId, targetRole.getCode()) == 0,
                ResultCode.GROUP_NOT_MEMBER);

        Long conversationId = conversationIdOf(groupId);
        Map<String, Object> roleExtra = new HashMap<>(4);
        roleExtra.put("userId", userId);
        roleExtra.put("role", targetRole.getCode());
        Map<String, Object> event = groupEvent(ACTION_ROLE_CHANGED, groupId, conversationId, roleExtra);
        afterCommit(() -> {
            noticeQuietly(conversationId, targetRole == GroupRole.ADMIN
                    ? nameOf(userId) + " 被设为管理员"
                    : nameOf(userId) + " 被取消管理员");
            pushQuietlyAll(groupMemberMapper.selectActiveMemberIds(groupId), event);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long operatorId, Long groupId, Long newOwnerId) {
        permissionChecker.requireOwner(groupId, operatorId);
        BusinessException.throwIf(newOwnerId == null, ResultCode.BAD_REQUEST, "接任群主不能为空");
        BusinessException.throwIf(newOwnerId.equals(operatorId), ResultCode.BAD_REQUEST, "不能转让给自己");
        permissionChecker.requireTargetMember(groupId, newOwnerId);

        // 三步必须同事务：先立新群主再降老群主，任何一步影响行数为 0 都说明群主已经换人，
        // 整体回滚，避免出现「两个群主」或「没有群主」的中间态
        BusinessException.throwIf(groupMemberMapper.promoteOwner(groupId, newOwnerId) == 0,
                ResultCode.BUSINESS_ERROR, "群主已变更，请刷新后重试");
        BusinessException.throwIf(groupMemberMapper.demoteOwner(groupId, operatorId) == 0,
                ResultCode.BUSINESS_ERROR, "群主已变更，请刷新后重试");
        BusinessException.throwIf(groupMapper.updateOwner(groupId, operatorId, newOwnerId) == 0,
                ResultCode.BUSINESS_ERROR, "群主已变更，请刷新后重试");

        Long conversationId = conversationIdOf(groupId);
        Map<String, Object> event = groupEvent(ACTION_TRANSFERRED, groupId, conversationId,
                Map.of("ownerId", newOwnerId));
        afterCommit(() -> {
            noticeQuietly(conversationId, "群主已转让给 " + nameOf(newOwnerId));
            pushQuietlyAll(groupMemberMapper.selectActiveMemberIds(groupId), event);
        });
        log.info("[转让群主] groupId={}, {} -> {}", groupId, operatorId, newOwnerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dismiss(Long operatorId, Long groupId) {
        permissionChecker.requireOwner(groupId, operatorId);
        // 成员列表要在置为已退群之前取，解散通知需要推给这批人
        List<Long> members = groupMemberMapper.selectActiveMemberIds(groupId);
        // dismiss 的条件里带 status = 1，两人同时解散只有一个能改到行，另一个当作已成功
        if (groupMapper.dismiss(groupId) == 0) {
            log.debug("[解散群] 群已被解散，幂等跳过: groupId={}", groupId);
            return;
        }
        groupMemberMapper.dismissAllMembers(groupId);
        Long conversationId = conversationIdOf(groupId);

        // 群会话与其成员关系都保留：历史消息还在里面，最后一条是解散通知，
        // 用户想让它从列表消失可以自己调会话的删除接口（只隐藏本端）
        Map<String, Object> event = groupEvent(ACTION_DISMISSED, groupId, conversationId, null);
        afterCommit(() -> {
            noticeQuietly(conversationId, nameOf(operatorId) + " 解散了群聊");
            pushQuietlyAll(members, event);
        });
        log.info("[解散群] groupId={}, 操作人={}, 影响成员={}", groupId, operatorId, members.size());
    }

    /* ==================== 禁言 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void muteMember(Long operatorId, Long groupId, Long userId, boolean muted, Integer minutes) {
        GroupPermissionChecker.GroupContext context = permissionChecker.requireOwnerOrAdmin(groupId, operatorId);
        GroupMember target = permissionChecker.requireTargetMember(groupId, userId);
        permissionChecker.requireManageable(context.member(), target);

        LocalDateTime endTime = muted && minutes != null ? LocalDateTime.now().plusMinutes(minutes) : null;
        if (muted) {
            BusinessException.throwIf(groupMemberMapper.mute(groupId, userId, endTime) == 0,
                    ResultCode.GROUP_NOT_MEMBER);
        } else {
            // 解除禁言幂等：本来就没被禁言也算成功，前端连点两次不应该报错
            groupMemberMapper.unmute(groupId, userId);
        }

        Long conversationId = conversationIdOf(groupId);
        Map<String, Object> extra = new HashMap<>(4);
        extra.put("userId", userId);
        extra.put("muted", muted);
        extra.put("muteEndTime", endTime);
        Map<String, Object> event = groupEvent(muted ? ACTION_MUTED : ACTION_UNMUTED, groupId, conversationId, extra);
        // 单人禁言不发群通知：一个 200 人群里禁言一个人不值得占用所有人的聊天流，只推给当事人
        afterCommit(() -> pushQuietly(userId, event));
        log.info("[群禁言] groupId={}, 操作人={}, 目标={}, 禁言={}, 到期={}",
                groupId, operatorId, userId, muted, endTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void muteAll(Long operatorId, Long groupId, boolean muteAll) {
        GroupPermissionChecker.GroupContext context = permissionChecker.requireOwnerOrAdmin(groupId, operatorId);
        if (context.group().isMuteAllOn() == muteAll) {
            return;
        }
        BusinessException.throwIf(groupMapper.updateMuteAll(groupId, muteAll ? 1 : 0) == 0,
                ResultCode.GROUP_DISMISSED);

        Long conversationId = conversationIdOf(groupId);
        Map<String, Object> event = groupEvent(ACTION_MUTE_ALL, groupId, conversationId,
                Map.of("muteAll", muteAll));
        afterCommit(() -> {
            // 全员禁言会影响每个人的输入框，必须在聊天流里留一条说明，否则成员只知道自己发不出消息
            noticeQuietly(conversationId, nameOf(operatorId) + (muteAll ? " 开启了全员禁言" : " 关闭了全员禁言"));
            pushQuietlyAll(groupMemberMapper.selectActiveMemberIds(groupId), event);
        });
    }

    /* ==================== 群内昵称与查询 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMyNickname(Long operatorId, Long groupId, String nicknameInGroup) {
        permissionChecker.requireMember(groupId, operatorId);
        String nickname = TextUtil.sanitize(nicknameInGroup, 32);
        var wrapper = Wrappers.<GroupMember>lambdaUpdate()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, operatorId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP);
        if (TextUtil.isBlank(nickname)) {
            wrapper.setSql("nickname_in_group = NULL");
        } else {
            wrapper.set(GroupMember::getNicknameInGroup, nickname);
        }
        groupMemberMapper.update(null, wrapper);
    }

    /**
     * 分页查群成员，仅群成员可查看。
     *
     * <p>{@code keyword} 刻意不支持：成员昵称与账号存在用户模块的库里，
     * 跨模块做模糊搜索只能把整群成员拉回来在内存里过滤，200 人的群尚可，
     * 上千人的群会直接把内存打满，不如让前端在当前页内自行筛选。
     */
    @Override
    public PageResult<GroupMemberVO> pageMembers(Long viewerId, Long groupId, PageQuery query) {
        permissionChecker.requireMember(groupId, viewerId);
        PageQuery safeQuery = query == null ? new PageQuery() : query;
        Page<GroupMember> page = groupMemberMapper.selectActiveMemberPage(safeQuery.toPage(), groupId);
        List<GroupMember> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(page.getCurrent(), page.getSize());
        }
        List<Long> userIds = rows.stream().map(GroupMember::getUserId).toList();
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(userIds);
        Set<Long> online = new HashSet<>(userQuerySpi.filterOnline(userIds));
        List<GroupMemberVO> records = rows.stream()
                .map(row -> GroupConvert.toMemberVO(row, users.get(row.getUserId()), online.contains(row.getUserId())))
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 写入或恢复一行群成员。
     *
     * <p>命中唯一键 {@code uk_group_user} 说明并发下已有人把同一批用户拉进群，
     * 此时转成恢复历史行（退群后重新入群也是这条路径），而不是让整批邀请因一个人的冲突而失败。
     *
     * @return 该行现在是否为在群状态
     */
    private boolean joinMember(Long groupId, Long userId, int role, LocalDateTime joinTime) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setMuted(0);
        member.setJoinTime(joinTime);
        member.setStatus(GroupMember.STATUS_IN_GROUP);
        try {
            groupMemberMapper.insert(member);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("[群加人] 命中唯一键，转为恢复历史行: groupId={}, userId={}", groupId, userId);
            return groupMemberMapper.restore(groupId, userId, role, joinTime) > 0;
        }
    }

    /**
     * 批量校验用户是否存在。
     *
     * <p>不存在的 ID 会在成员表里留下一行永远查不到资料的脏数据，成员列表上表现为一个空白行，
     * 宁可整批失败也不要静默吞掉。
     */
    private Map<Long, UserBriefDTO> loadUsers(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(userIds);
        List<Long> missing = userIds.stream().filter(id -> !users.containsKey(id)).toList();
        BusinessException.throwIf(!missing.isEmpty(), ResultCode.USER_NOT_FOUND);
        return users;
    }

    /**
     * 组装群详情 VO，群主资料与成员数在这里补齐。
     */
    private GroupVO toVO(Group group, GroupMember myMember, Long conversationId) {
        UserBriefDTO owner = group.getOwnerId() == null ? null : userQuerySpi.getById(group.getOwnerId());
        int memberCount = (int) groupMemberMapper.countActive(group.getId());
        return GroupConvert.toVO(group, myMember, owner, memberCount, conversationId);
    }

    private Map<Long, Integer> memberCounts(Collection<Long> groupIds) {
        Map<Long, Integer> counts = new HashMap<>(groupIds.size());
        for (GroupMemberCount row : groupMemberMapper.selectMemberCounts(groupIds)) {
            counts.put(row.getGroupId(), row.getMemberCount() == null ? 0 : row.getMemberCount());
        }
        return counts;
    }

    /**
     * 一次拿到我所有群会话的「群 ID -&gt; 会话 ID」映射。
     *
     * <p>复用 {@code listByUser} 而不是逐群 {@code findGroup}：会话列表本来就要查一遍，
     * 逐群查会把一次列表请求放大成几十次数据库往返。用户手动隐藏过某个群会话时这里查不到，
     * 对应的 {@code conversationId} 为空，前端按「无可跳转会话」处理即可。
     */
    private Map<Long, Long> groupConversationIds(Long userId) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null) {
            return Map.of();
        }
        try {
            Map<Long, Long> result = new HashMap<>();
            for (ConversationBriefDTO brief : spi.listByUser(userId)) {
                if (Objects.equals(brief.getType(), ConvType.GROUP.getCode()) && brief.getTargetId() != null) {
                    result.putIfAbsent(brief.getTargetId(), brief.getConversationId());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[我的群聊] 查询群会话失败，会话 ID 留空: userId={}, err={}", userId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 只读地查群会话 ID，会话模块不可用或群会话尚未建好时返回 {@code null}。
     */
    private Long conversationIdOf(Long groupId) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null) {
            return null;
        }
        return spi.findGroup(groupId);
    }

    /**
     * 建群时创建群会话。
     *
     * <p>这里不 catch 异常：{@code getOrCreateGroup} 自带事务并加入当前事务，一旦它抛异常，
     * 事务已被标记 rollback-only，吞掉异常只会让提交时抛出一个更难懂的
     * {@code UnexpectedRollbackException}。没有会话的群本身也没有意义，一起失败更合理。
     */
    private Long createGroupConversation(Long groupId, Collection<Long> memberIds) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null) {
            log.warn("[建群] 会话模块不可用，群已创建但没有群会话: groupId={}", groupId);
            return null;
        }
        return spi.getOrCreateGroup(groupId, memberIds);
    }

    private void joinConversation(Long conversationId, Collection<Long> userIds) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null || conversationId == null || userIds.isEmpty()) {
            return;
        }
        spi.addMembers(conversationId, userIds);
    }

    private void leaveConversation(Long conversationId, Long userId) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null || conversationId == null) {
            return;
        }
        spi.removeMember(conversationId, userId);
    }

    /**
     * 发一条群系统通知，失败只记日志。必须在事务提交之后调用，原因见类注释。
     */
    private void noticeQuietly(Long conversationId, String content) {
        if (conversationId == null) {
            return;
        }
        MessageSpi spi = messageSpiProvider.getIfAvailable();
        if (spi == null) {
            return;
        }
        try {
            spi.sendSystemNotice(conversationId, content);
        } catch (Exception e) {
            log.warn("[群组] 系统通知发送失败，忽略: conversationId={}, content={}, err={}",
                    conversationId, content, e.getMessage());
        }
    }

    private void pushQuietly(Long userId, Map<String, Object> data) {
        PushSpi spi = pushSpiProvider.getIfAvailable();
        if (spi == null || userId == null) {
            return;
        }
        try {
            spi.pushToUser(userId, WsPacket.of(WsMessageType.NOTIFY, data));
        } catch (Exception e) {
            log.debug("[群组] 推送失败，目标可能不在线: userId={}, err={}", userId, e.getMessage());
        }
    }

    private void pushQuietlyAll(Collection<Long> userIds, Map<String, Object> data) {
        PushSpi spi = pushSpiProvider.getIfAvailable();
        if (spi == null || userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            spi.pushToUsers(userIds, null, WsPacket.of(WsMessageType.NOTIFY, data));
        } catch (Exception e) {
            log.debug("[群组] 批量推送失败: 人数={}, err={}", userIds.size(), e.getMessage());
        }
    }

    /**
     * 组装群事件的 NOTIFY 载荷，前端按 {@code action} 决定刷新哪一块数据。
     */
    private Map<String, Object> groupEvent(String action, Long groupId, Long conversationId, Map<String, ?> extra) {
        Map<String, Object> data = new HashMap<>(8);
        data.put("biz", BIZ_GROUP);
        data.put("action", action);
        data.put("groupId", groupId);
        if (conversationId != null) {
            data.put("conversationId", conversationId);
        }
        if (extra != null) {
            data.putAll(extra);
        }
        return data;
    }

    private String name(Map<Long, UserBriefDTO> users, Long userId) {
        return GroupConvert.displayName(null, users.get(userId), userId);
    }

    private String nameOf(Long userId) {
        return GroupConvert.displayName(null, userId == null ? null : userQuerySpi.getById(userId), userId);
    }

    /**
     * 把一批用户拼成通知文案里的名字串。
     *
     * <p>超过 {@link #MAX_NAMES_IN_NOTICE} 个人时折叠成「张三、李四、王五 等 12 人」：
     * 群通知是聊天流里的一条消息，一次拉 100 人就把 100 个昵称全拼进去会占满整屏。
     */
    private String names(Map<Long, UserBriefDTO> users, List<Long> userIds) {
        List<String> shown = userIds.stream()
                .limit(MAX_NAMES_IN_NOTICE)
                .map(id -> name(users, id))
                .toList();
        return userIds.size() > shown.size()
                ? String.join("、", shown) + " 等 " + userIds.size() + " 人"
                : String.join("、", shown);
    }

    /**
     * 去重并剔除非正数 ID，保持原有顺序。
     *
     * <p>前端从好友列表整批勾选时很容易带上重复项，0 与负数则是伪造请求的常见形态，
     * 在这里统一收敛，后续逻辑就不必再判空判重。
     */
    private List<Long> distinctPositive(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    /**
     * 在事务提交后执行副作用；当前没有活动事务时立即执行，保证脱离 Web 请求单独调用也能生效。
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
