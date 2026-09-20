package com.im.friend.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.enums.FriendStatus;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.util.TextUtil;
import com.im.friend.entity.Friend;
import com.im.friend.mapper.FriendMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FriendRelationSpi} 的实现，供 im-message / im-conversation / im-user 等模块判定好友关系。
 *
 * <p>关系为双向双行存储，拉黑是单向语义：每行的 {@code status} 只表达「持有者对好友」的态度。
 * 因此 {@code isFriend} 要求两行都存在且都未拉黑，只要有一方拉黑即视为非好友，
 * 消息发送前置校验据此拦截。
 */
@Service
@RequiredArgsConstructor
public class FriendRelationSpiImpl implements FriendRelationSpi {

    private final FriendMapper friendMapper;

    @Override
    public boolean isFriend(Long a, Long b) {
        if (isInvalidPair(a, b)) {
            return false;
        }
        List<Friend> both = friendMapper.selectBothDirections(a, b);
        return both.size() == 2 && both.stream().noneMatch(Friend::isBlocked);
    }

    @Override
    public boolean isBlockedBy(Long a, Long b) {
        if (isInvalidPair(a, b)) {
            return false;
        }
        // 只看 a 持有的那一行：status=2 表示 a 把 b 拉黑
        Friend relation = friendMapper.selectRelation(a, b);
        return relation != null && relation.isBlocked();
    }

    @Override
    public boolean isBlockedEitherWay(Long a, Long b) {
        if (isInvalidPair(a, b)) {
            return false;
        }
        return friendMapper.selectBothDirections(a, b).stream().anyMatch(Friend::isBlocked);
    }

    @Override
    public void unblockSilently(Long a, Long b) {
        if (isInvalidPair(a, b)) {
            return;
        }
        // 把「查了再改」压成一条条件更新：status 不是拉黑态时自然不命中，无需先读也不能报错
        friendMapper.update(null, Wrappers.<Friend>lambdaUpdate()
                .set(Friend::getStatus, FriendStatus.NORMAL.getCode())
                .eq(Friend::getUserId, a)
                .eq(Friend::getFriendId, b)
                .eq(Friend::getStatus, FriendStatus.BLOCKED.getCode()));
    }

    @Override
    public String getRemark(Long a, Long b) {
        if (isInvalidPair(a, b)) {
            return null;
        }
        Friend relation = friendMapper.selectRelation(a, b);
        return relation == null ? null : relation.getRemark();
    }

    @Override
    public List<Long> listFriendIds(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return friendMapper.selectNormalByUserId(userId).stream()
                .map(Friend::getFriendId)
                .distinct()
                .toList();
    }

    @Override
    public Map<Long, String> getRemarks(Long userId, Collection<Long> friendIds) {
        if (userId == null || friendIds == null || friendIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> remarks = new HashMap<>();
        for (Friend relation : friendMapper.selectRelations(userId, friendIds)) {
            // 只收录确实设过备注的关系，未设备注的交给调用方回退到昵称
            if (TextUtil.isNotBlank(relation.getRemark())) {
                remarks.put(relation.getFriendId(), relation.getRemark());
            }
        }
        return remarks;
    }

    /**
     * 参数非法或对端为同一人时直接短路，避免无意义的数据库往返。
     */
    private boolean isInvalidPair(Long a, Long b) {
        return a == null || b == null || a.equals(b);
    }
}
