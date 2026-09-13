package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.enums.FriendStatus;
import com.im.friend.entity.Friend;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 好友关系 Mapper。
 *
 * <p>便捷查询以 {@code default} 方法形式内聚在此，避免 Service 层散落 wrapper 拼装细节。
 */
@Mapper
public interface FriendMapper extends BaseMapper<Friend> {

    /**
     * 查询单向关系行 {@code (userId -> friendId)}。
     *
     * @return 关系不存在时返回 {@code null}
     */
    default Friend selectRelation(Long userId, Long friendId) {
        return selectOne(Wrappers.<Friend>lambdaQuery()
                .eq(Friend::getUserId, userId)
                .eq(Friend::getFriendId, friendId)
                .last("LIMIT 1"));
    }

    /**
     * 查询用户持有的全部关系行（含已拉黑），按建立时间倒序。
     */
    default List<Friend> selectByUserId(Long userId) {
        return selectList(Wrappers.<Friend>lambdaQuery()
                .eq(Friend::getUserId, userId)
                .orderByDesc(Friend::getCreateTime)
                .orderByDesc(Friend::getId));
    }

    /**
     * 查询用户的全部正常（未拉黑）好友关系行。
     */
    default List<Friend> selectNormalByUserId(Long userId) {
        return selectList(Wrappers.<Friend>lambdaQuery()
                .eq(Friend::getUserId, userId)
                .eq(Friend::getStatus, FriendStatus.NORMAL.getCode()));
    }

    /**
     * 批量查询我对一批用户的关系行，供会话列表一次性回填备注。
     */
    default List<Friend> selectRelations(Long userId, Collection<Long> friendIds) {
        if (friendIds == null || friendIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Friend>lambdaQuery()
                .eq(Friend::getUserId, userId)
                .in(Friend::getFriendId, friendIds));
    }

    /**
     * 一次查出 {@code (A->B)} 与 {@code (B->A)} 两个方向的关系行。
     *
     * <p>好友判定需要同时看两边的拉黑状态，用本方法可把两次往返压成一次。
     *
     * @return 0 / 1 / 2 行，分别对应无关系、单向残留、正常双向
     */
    default List<Friend> selectBothDirections(Long userA, Long userB) {
        return selectList(Wrappers.<Friend>lambdaQuery()
                .and(w -> w.eq(Friend::getUserId, userA).eq(Friend::getFriendId, userB))
                .or(w -> w.eq(Friend::getUserId, userB).eq(Friend::getFriendId, userA)));
    }

    /**
     * 物理删除双向两行：{@code (A->B)} 与 {@code (B->A)}。
     *
     * <p>条件刻意用两个 {@code and(...)} 分组包裹，保证生成
     * {@code (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)}，
     * 不会与后续可能追加的条件产生优先级歧义。
     *
     * @return 实际删除行数，正常为 2
     */
    default int deleteBoth(Long userId, Long friendId) {
        return delete(Wrappers.<Friend>lambdaQuery()
                .and(w -> w.eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId))
                .or(w -> w.eq(Friend::getUserId, friendId).eq(Friend::getFriendId, userId)));
    }
}
