package com.im.group.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminGroupDTO;
import com.im.common.domain.AdminGroupMemberQueryDTO;
import com.im.common.domain.AdminGroupQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminGroupSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.group.dto.po.GroupMemberCount;
import com.im.group.entity.Group;
import com.im.group.entity.GroupJoinRequest;
import com.im.group.entity.GroupMember;
import com.im.group.entity.GroupRobot;
import com.im.group.mapper.GroupJoinRequestMapper;
import com.im.group.mapper.GroupMapper;
import com.im.group.mapper.GroupMemberMapper;
import com.im.group.mapper.GroupRobotMapper;
import com.im.group.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台群组管理契约的实现（{@link AdminGroupSpi}），读 {@code im_group} 族四张表。
 *
 * <p>两个写动作都交回 {@link GroupService} 的 {@code force} 分支，本类不碰成员行也不碰状态列：
 * 解散一个群要顺带把成员置为退群、失效群缓存、发群内系统通知、给全体在线成员推 WebSocket 事件，
 * 这套动作在用户侧只有一份实现。后台另写一份的结局是两边行为分叉，
 * 而分叉的第一现场永远是「群没了但人还能发消息」。
 *
 * <p>台账与成员页的昵称一律批量补（一页一次 {@code IN}），不逐行调 {@code getById}：
 * 后者在三级缓存上看起来人畜无害，实际是把一次列表请求变成五十次 Redis 往返加五十次回源判断。
 * 群名关键词与成员关键词都要「先把名字变成 ID」，那一步走
 * {@link UserQuerySpi#searchUserIds}——群模块读不到 {@code im_user}，星型架构不许它读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupAdminSpiImpl implements AdminGroupSpi {

    /** 单页上限，与直播 / 视频 / 钱包后台同口径 */
    private static final long MAX_PAGE_SIZE = 100;
    private static final int KEYWORD_MAX = 30;
    /** 档案里的成员快照条数，真分页走 {@link #pageMembers} */
    private static final int SNAPSHOT_MEMBERS = 20;
    /** 档案里的入群申请条数（含已处理） */
    private static final int SNAPSHOT_REQUESTS = 20;
    /** 反查用户 ID 的行数上限：一个群的人数上限就是这里的天花板，多给也没用 */
    private static final int USER_ID_HIT_LIMIT = 500;
    private static final String DELETED_NICKNAME = "已注销用户";

    private final GroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final GroupJoinRequestMapper joinRequestMapper;
    private final GroupRobotMapper robotMapper;
    private final UserQuerySpi userQuerySpi;
    private final GroupService groupService;

    @Override
    public PageResult<AdminGroupDTO> pageGroups(AdminGroupQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = trimTo(query.getKeyword(), KEYWORD_MAX);
        // ownerId 传 0 与不传是一回事：前端「不限群主」的下拉框常用 0 而不是 null
        Long ownerId = query.getOwnerId() == null || query.getOwnerId() <= 0 ? null : query.getOwnerId();

        LambdaQueryWrapper<Group> wrapper = Wrappers.<Group>lambdaQuery()
                .eq(ownerId != null, Group::getOwnerId, ownerId)
                .eq(query.getStatus() != null, Group::getStatus, query.getStatus())
                .like(keyword != null, Group::getName, keyword)
                .ge(query.getStartTime() != null, Group::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, Group::getCreateTime, query.getEndTime())
                // 雪花 ID 随时间递增，倒着走主键就是「新建的群在前」且不带 filesort；
                // im_group 上除 idx_owner 与 uk_invite_code 外没有别的索引，按建群时间排要全表扫
                .orderByDesc(Group::getId);

        Page<Group> page = groupMapper.selectPage(new Page<>(current, size), wrapper);
        List<Group> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> ownerIds = new HashSet<>();
        List<Long> groupIds = new ArrayList<>(rows.size());
        for (Group group : rows) {
            groupIds.add(group.getId());
            if (group.getOwnerId() != null) {
                ownerIds.add(group.getOwnerId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(ownerIds);
        Map<Long, Integer> counts = memberCounts(groupIds);
        List<AdminGroupDTO> records = new ArrayList<>(rows.size());
        for (Group group : rows) {
            // 列表不带成员/申请/机器人三段快照，也不带 pendingJoinCount（那要逐群一次计数）
            records.add(toView(group, displayOf(briefs.get(group.getOwnerId())),
                    counts.get(group.getId()), null, null, null, null));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminGroupDTO detail(Long groupId) {
        if (groupId == null) {
            return null;
        }
        // selectById 而不是 selectNormalById：已解散的群必须还能查得到，否则「这个群什么时候没的」没有答案
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            return null;
        }
        UserBriefDTO owner = group.getOwnerId() == null ? null : userQuerySpi.getById(group.getOwnerId());
        return toView(group, displayOf(owner), (int) memberMapper.countActive(groupId),
                joinRequestMapper.countPending(groupId),
                memberSnapshot(groupId),
                requestSnapshot(groupId),
                robotSnapshot(groupId));
    }

    @Override
    public PageResult<AdminGroupDTO.MemberItem> pageMembers(AdminGroupMemberQueryDTO query) {
        BusinessException.throwIf(query.getGroupId() == null, ResultCode.BAD_REQUEST, "groupId 不能为空");
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);

        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, query.getGroupId())
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .eq(query.getRole() != null, GroupMember::getRole, query.getRole());
        String keyword = trimTo(query.getKeyword(), KEYWORD_MAX);
        if (keyword != null) {
            // 群内昵称在本表，账号昵称在 im_user：后者只能先变成 ID 再拼 IN，两个条件取或
            List<Long> hits = userQuerySpi.searchUserIds(keyword, USER_ID_HIT_LIMIT);
            wrapper.and(w -> {
                w.like(GroupMember::getNicknameInGroup, keyword);
                if (!hits.isEmpty()) {
                    w.or().in(GroupMember::getUserId, hits);
                }
            });
        }
        // 管理层顶到前面：这一屏第一眼要找的是「谁是这个群的组织者」，不是最新入群的人
        wrapper.orderByAsc(GroupMember::getRole)
                .orderByAsc(GroupMember::getJoinTime)
                .orderByAsc(GroupMember::getId);

        Page<GroupMember> page = memberMapper.selectPage(new Page<>(current, size), wrapper);
        List<GroupMember> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIdsOf(rows));
        List<AdminGroupDTO.MemberItem> records = new ArrayList<>(rows.size());
        for (GroupMember member : rows) {
            records.add(toMemberView(member, displayOf(briefs.get(member.getUserId()))));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminGroupDTO forceDismiss(Long groupId, Long adminId) {
        groupService.forceDismiss(groupId, adminId);
        log.info("[后台群管理] operator={} 强制解散群: groupId={}", adminId, groupId);
        return detail(groupId);
    }

    @Override
    public AdminGroupDTO forceMuteAll(Long groupId, boolean muteAll, Long adminId) {
        groupService.forceMuteAll(groupId, muteAll, adminId);
        log.info("[后台群管理] operator={} {}全员禁言: groupId={}",
                adminId, muteAll ? "开启" : "关闭", groupId);
        return detail(groupId);
    }

    /* ==================== 内部装配 ==================== */

    /** 一次 {@code GROUP BY group_id} 算完整页的成员数，成员全退群的群不在结果里，取值时按 0 处理 */
    private Map<Long, Integer> memberCounts(Collection<Long> groupIds) {
        Map<Long, Integer> result = new HashMap<>(groupIds.size());
        for (GroupMemberCount row : memberMapper.selectMemberCounts(groupIds)) {
            if (row.getGroupId() != null) {
                result.put(row.getGroupId(), row.getMemberCount() == null ? 0 : row.getMemberCount());
            }
        }
        return result;
    }

    private List<AdminGroupDTO.MemberItem> memberSnapshot(Long groupId) {
        List<GroupMember> rows = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .orderByDesc(GroupMember::getJoinTime)
                .orderByDesc(GroupMember::getId)
                .last("LIMIT " + SNAPSHOT_MEMBERS));
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIdsOf(rows));
        List<AdminGroupDTO.MemberItem> result = new ArrayList<>(rows.size());
        for (GroupMember member : rows) {
            result.add(toMemberView(member, displayOf(briefs.get(member.getUserId()))));
        }
        return result;
    }

    private List<AdminGroupDTO.JoinRequestItem> requestSnapshot(Long groupId) {
        List<GroupJoinRequest> rows = joinRequestMapper.selectList(Wrappers.<GroupJoinRequest>lambdaQuery()
                .eq(GroupJoinRequest::getGroupId, groupId)
                .orderByDesc(GroupJoinRequest::getCreateTime)
                .orderByDesc(GroupJoinRequest::getId)
                .last("LIMIT " + SNAPSHOT_REQUESTS));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        for (GroupJoinRequest row : rows) {
            if (row.getUserId() != null) {
                ids.add(row.getUserId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(ids);
        List<AdminGroupDTO.JoinRequestItem> result = new ArrayList<>(rows.size());
        for (GroupJoinRequest row : rows) {
            result.add(AdminGroupDTO.JoinRequestItem.builder()
                    .requestId(row.getId())
                    .userId(row.getUserId())
                    .nickname(displayOf(briefs.get(row.getUserId())))
                    .status(row.getStatus())
                    .message(row.getMessage())
                    .source(row.getSource())
                    .handleUserId(row.getHandleUserId())
                    .handleTime(row.getHandleTime())
                    .createTime(row.getCreateTime())
                    .build());
        }
        return result;
    }

    /** 机器人段<b>不含 webhookToken</b>：那是外部系统代发群消息的凭证，与邀请码同一条红线 */
    private List<AdminGroupDTO.RobotItem> robotSnapshot(Long groupId) {
        List<GroupRobot> rows = robotMapper.listByGroup(groupId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        for (GroupRobot row : rows) {
            if (row.getCreatorId() != null) {
                ids.add(row.getCreatorId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(ids);
        List<AdminGroupDTO.RobotItem> result = new ArrayList<>(rows.size());
        for (GroupRobot row : rows) {
            result.add(AdminGroupDTO.RobotItem.builder()
                    .robotId(row.getId())
                    .name(row.getName())
                    .avatar(row.getAvatar())
                    .description(row.getDescription())
                    .status(row.getStatus())
                    .creatorId(row.getCreatorId())
                    .creatorName(displayOf(briefs.get(row.getCreatorId())))
                    .createTime(row.getCreateTime())
                    .build());
        }
        return result;
    }

    private AdminGroupDTO toView(Group group, String ownerName, Integer memberCount, Long pendingJoinCount,
                                 List<AdminGroupDTO.MemberItem> members,
                                 List<AdminGroupDTO.JoinRequestItem> joinRequests,
                                 List<AdminGroupDTO.RobotItem> robots) {
        return AdminGroupDTO.builder()
                .groupId(group.getId())
                .name(group.getName())
                .avatar(group.getAvatar())
                .notice(group.getNotice())
                .ownerId(group.getOwnerId())
                .ownerName(ownerName)
                // 成员数为 null 表示「这一行是列表行且没算出来」（成员全部退群的群不在聚合结果里），补 0 而不是留空
                .memberCount(memberCount == null ? 0L : memberCount.longValue())
                .maxMember(group.getMaxMember())
                .muteAll(group.getMuteAll())
                .joinApproval(group.getJoinApproval())
                .status(group.getStatus())
                .createTime(group.getCreateTime())
                .updateTime(group.getUpdateTime())
                .pendingJoinCount(pendingJoinCount)
                .members(members)
                .joinRequests(joinRequests)
                .robots(robots)
                .build();
    }

    private AdminGroupDTO.MemberItem toMemberView(GroupMember member, String nickname) {
        return AdminGroupDTO.MemberItem.builder()
                .userId(member.getUserId())
                .nickname(nickname)
                .nicknameInGroup(member.getNicknameInGroup())
                .role(member.getRole())
                .muted(member.getMuted())
                .muteEndTime(member.getMuteEndTime())
                .joinTime(member.getJoinTime())
                .status(member.getStatus())
                .build();
    }

    private static Set<Long> userIdsOf(List<GroupMember> rows) {
        Set<Long> ids = new HashSet<>();
        for (GroupMember row : rows) {
            if (row.getUserId() != null) {
                ids.add(row.getUserId());
            }
        }
        return ids;
    }

    /** 昵称优先，昵称空着退到账号，查不到这个人就是「已注销用户」；群主被注销而群还活着是真实存在的情况 */
    private static String displayOf(UserBriefDTO user) {
        if (user == null) {
            return DELETED_NICKNAME;
        }
        return TextUtil.isBlank(user.getNickname()) ? user.getUsername() : user.getNickname();
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
