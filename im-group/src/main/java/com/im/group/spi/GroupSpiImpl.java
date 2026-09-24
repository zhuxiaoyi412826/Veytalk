package com.im.group.spi;

import com.im.common.cache.ThreeLevelCache;
import com.im.common.constant.ImConstants;
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

import java.util.ArrayList;
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
 *
 * <p>群的静态资料（群名/头像/公告/群主）走三级缓存，成员数与查看者视角字段永远实时查：
 * 成员数随进出群波动、视角字段因人而异，两者都不适合进全局缓存。缓存里存的是
 * 不带这些字段的 DTO，返回前用 {@code toBuilder} 复制一份再补，避免污染 L1 共享引用。
 * 失效点在 GroupServiceImpl 的 update/transfer/dismiss（afterCommit 里）。
 */
@Component
@RequiredArgsConstructor
public class GroupSpiImpl implements GroupSpi {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ThreeLevelCache cache;

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
        GroupBriefDTO base = cache.get(cacheKey(groupId), GroupBriefDTO.class, () -> {
            Group group = groupMapper.selectById(groupId);
            return group == null ? null : GroupConvert.toBriefDTO(group, null, null);
        });
        if (base == null) {
            return null;
        }
        GroupMember myMember = viewerId == null ? null : groupMemberMapper.selectActive(groupId, viewerId);
        boolean joined = myMember != null && myMember.isInGroup();
        return base.toBuilder()
                .memberCount((int) groupMemberMapper.countActive(groupId))
                .myRole(joined ? myMember.getRole() : null)
                .myNickname(joined ? myMember.getNicknameInGroup() : null)
                .build();
    }

    /**
     * 批量取群资料，会话列表渲染群聊条目时的唯一入口。
     *
     * <p>静态资料逐个 peek、未命中部分一次批量回源；成员数仍按群 ID 集合一次 GROUP BY 实时查。
     * {@code myRole} / {@code myNickname} 是查看者视角字段，批量场景下没有查看者，留空由调用方按需单独补。
     */
    @Override
    public Map<Long, GroupBriefDTO> listBriefs(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, GroupBriefDTO> statics = new HashMap<>(groupIds.size());
        List<Long> misses = new ArrayList<>(groupIds.size());
        for (Long id : groupIds) {
            if (id == null) {
                continue;
            }
            GroupBriefDTO cached = cache.peek(cacheKey(id), GroupBriefDTO.class);
            if (cached != null) {
                statics.put(id, cached);
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            List<Group> groups = groupMapper.selectByIds(misses);
            Map<Long, GroupBriefDTO> found = new HashMap<>(groups == null ? 0 : groups.size());
            if (groups != null) {
                for (Group group : groups) {
                    found.put(group.getId(), GroupConvert.toBriefDTO(group, null, null));
                }
            }
            for (Long id : misses) {
                GroupBriefDTO brief = found.get(id);
                cache.put(cacheKey(id), brief, null);
                if (brief != null) {
                    statics.put(id, brief);
                }
            }
        }
        if (statics.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>(statics.size());
        for (GroupMemberCount row : groupMemberMapper.selectMemberCounts(new ArrayList<>(statics.keySet()))) {
            counts.put(row.getGroupId(), row.getMemberCount() == null ? 0 : row.getMemberCount());
        }
        Map<Long, GroupBriefDTO> result = new HashMap<>(statics.size());
        for (Map.Entry<Long, GroupBriefDTO> entry : statics.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toBuilder()
                    .memberCount(counts.getOrDefault(entry.getKey(), 0))
                    .build());
        }
        return result;
    }

    @Override
    public boolean exists(Long groupId) {
        return groupId != null && groupMapper.selectNormalById(groupId) != null;
    }

    private static String cacheKey(Long groupId) {
        return ImConstants.CACHE_GROUP_BRIEF_PREFIX + groupId;
    }
}
