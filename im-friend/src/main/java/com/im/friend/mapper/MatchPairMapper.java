package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MatchPair;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 配对关系 Mapper。
 *
 * <p>入参一律先经 {@link #lowId} / {@link #highId} 排序再查，因为 {@code uk_pair} 建在
 * {@code (user_a_id, user_b_id)} 上、且约定 a &lt; b：不排序的调用会查到一个永远不存在的方向，
 * 表现是「明明配对成功了，刷新以后又变成没配过」。
 */
@Mapper
public interface MatchPairMapper extends BaseMapper<MatchPair> {

    /** 一对人在表里只可能有一行，所以这是查唯一键，不需要 LIMIT 1。 */
    default MatchPair selectByUsers(Long userX, Long userY) {
        return selectOne(Wrappers.<MatchPair>lambdaQuery()
                .eq(MatchPair::getUserAId, lowId(userX, userY))
                .eq(MatchPair::getUserBId, highId(userX, userY)));
    }

    /**
     * 我的全部配对中关系，按配对时间倒序。
     *
     * <p>a、b 两列都要看，条件必须写在 {@code and(...)} 里包一层括号：
     * 直接 {@code .eq(status).or()} 会让 status 只约束其中一个方向，另一个方向把已解除的也带出来。
     */
    default List<MatchPair> selectActiveByUser(Long userId) {
        return selectList(Wrappers.<MatchPair>lambdaQuery()
                .eq(MatchPair::getStatus, MatchPair.STATUS_ACTIVE)
                .and(w -> w.eq(MatchPair::getUserAId, userId).or().eq(MatchPair::getUserBId, userId))
                .orderByDesc(MatchPair::getCreateTime)
                .orderByDesc(MatchPair::getId));
    }

    /** 我的配对列表分页，同上按 {@code (user_a_id | user_b_id)} 两个方向取。 */
    default IPage<MatchPair> selectByUserPage(IPage<MatchPair> page, Long userId, Integer status) {
        return selectPage(page, Wrappers.<MatchPair>lambdaQuery()
                .eq(status != null, MatchPair::getStatus, status)
                .and(w -> w.eq(MatchPair::getUserAId, userId).or().eq(MatchPair::getUserBId, userId))
                .orderByDesc(MatchPair::getCreateTime)
                .orderByDesc(MatchPair::getId));
    }

    /**
     * 带状态条件的更新，兼作乐观锁。
     *
     * @return 影响行数，0 表示状态已被并发请求改走（两个人同时点解除时就是这种情况）
     */
    default int updateStatusIf(Long pairId, int fromStatus, int toStatus) {
        return update(null, Wrappers.<MatchPair>lambdaUpdate()
                .set(MatchPair::getStatus, toStatus)
                .eq(MatchPair::getId, pairId)
                .eq(MatchPair::getStatus, fromStatus));
    }

    /** 排序写入：{@code user_a_id} 恒为较小的一方。 */
    static Long lowId(Long x, Long y) {
        return x.compareTo(y) <= 0 ? x : y;
    }

    /** 排序写入：{@code user_b_id} 恒为较大的一方。 */
    static Long highId(Long x, Long y) {
        return x.compareTo(y) <= 0 ? y : x;
    }
}
