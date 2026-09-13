package com.im.group.spi;

import com.im.common.domain.GroupBriefDTO;
import com.im.common.spi.GroupSpi;
import com.im.group.convert.GroupConvert;
import com.im.group.dto.po.GroupMemberCount;
import com.im.group.entity.Group;
import com.im.group.entity.GroupMember;
import com.im.group.mapper.GroupMapper;
import com.im.group.mapper.GroupMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GroupSpi} 在本模块的实现，供 im-conversation / im-message 跨模块调用。
 *
 * <p>这一层直接用 Mapper 而不经过 {@link com.im.group.service.GroupService}：SPI 的语义是
 * 「查不到就返回 null / false」，而 Service 的语义是「查不到就抛 6xxx 异常」。消息模块在
 * 发送校验里需要的是前者——它要自己决定回什么错误码，不希望异常从另一个模块穿进来。
 *
 * <p>禁言与成员判定是群消息发送的必经路径，因此这里全部走主键或唯一键定位，不做模糊查询。
 */
@Component
@RequiredArgsConstructor
public class GroupSpiImpl implements GroupSpi {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;

    @Override
    public boolean isMember(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        return groupMemberMapper.selectActive(groupId, userId) != null;
    }

    @Override
    public Integer getRole(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return null;
        }
        GroupMember member = groupMemberMapper.selectActive(groupId, userId);
        return member == null ? null : member.getRole();
    }

    /**
     * 禁言判定要同时看群与成员两张表，因此是两次查询。
     *
     * <p>看着像热点，但两次都是主键 / 唯一键定位；加一层缓存就得处理「群主一改开关就要失效」
     * 的一致性问题，而禁言判错的后果是用户明明被禁言却能发言，这个代价远高于两次索引查询。
     */
    @Override
    public boolean isMuted(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        GroupMember member = groupMemberMapper.selectActive(groupId, userId);
        if (member == null) {
            return false;
        }
        return GroupConvert.mutedNow(groupMapper.selectNormalById(groupId), member);
    }

    @Override
    public List<Long> getMemberIds(Long groupId) {
        return groupId == null ? List.of() : groupMemberMapper.selectActiveMemberIds(groupId);
    }

    /**
     * 用 {@code selectById} 而不是 {@code selectNormalById}：这个方法是给展示用的，
     * 群解散后会话列表里的历史消息仍然要显示「来自 xx 群」，查不到就会变成一片空白。
     * 「这个群还能不能用」由 {@link #exists} 与 {@link #isMember} 回答。
     */
    @Override
    public GroupBriefDTO getBrief(Long groupId, Long viewerId) {
        if (groupId == null) {
            return null;
        }
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            return null;
        }
        GroupMember myMember = viewerId == null ? null : groupMemberMapper.selectActive(groupId, viewerId);
        return GroupConvert.toBriefDTO(group, myMember, (int) groupMemberMapper.countActive(groupId));
    }

    /**
     * 批量取群资料，会话列表渲染群聊条目时的唯一入口。
     *
     * <p>群资料与成员数各一次批量查询，与会话数量无关；{@code myRole} / {@code myNickname}
     * 是查看者视角字段，批量场景下没有查看者，留空由调用方按需单独补。
     */
    @Override
    public Map<Long, GroupBriefDTO> listBriefs(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        List<Group> groups = groupMapper.selectByIds(groupIds);
        if (groups.isEmpty()) {
            return Map.of();
        }
        List<Long> found = groups.stream().map(Group::getId).toList();
        Map<Long, Integer> counts = new HashMap<>(found.size());
        for (GroupMemberCount row : groupMemberMapper.selectMemberCounts(found)) {
            counts.put(row.getGroupId(), row.getMemberCount() == null ? 0 : row.getMemberCount());
        }
        Map<Long, GroupBriefDTO> result = new HashMap<>(groups.size());
        for (Group group : groups) {
            result.put(group.getId(), GroupConvert.toBriefDTO(group, null, counts.getOrDefault(group.getId(), 0)));
        }
        return result;
    }

    @Override
    public boolean exists(Long groupId) {
        return groupId != null && groupMapper.selectNormalById(groupId) != null;
    }
}
