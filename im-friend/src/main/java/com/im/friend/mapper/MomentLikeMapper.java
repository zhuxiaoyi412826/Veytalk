package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MomentLike;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 动态点赞 Mapper。
 *
 * <p>这张表<b>没有 {@code deleted} 列</b>，取消点赞就是 {@link #deleteBy} 物理删行，
 * 唯一键 {@code uk_moment_user} 才会释放出来让同一个人再点一次（与 {@code im_friend} 同一口径）。
 *
 * <p>{@link #deleteBy} 的返回值是<b>幂等性的唯一依据</b>：0 行意味着这次「取消」根本没取消掉什么，
 * 此时服务层不该再去减 {@code im_moment.like_count}，否则连点两次取消就会把计数拖下去。
 */
@Mapper
public interface MomentLikeMapper extends BaseMapper<MomentLike> {

    /**
     * 一批动态里我已经赞过哪些。
     *
     * <p>Feed 上每条动态的红心是实心还是空心就靠它：一屏十几条逐条查就是十几次往返。
     */
    default List<Long> selectLikedMomentIds(Long userId, Collection<Long> momentIds) {
        if (userId == null || momentIds == null || momentIds.isEmpty()) {
            return List.of();
        }
        List<MomentLike> rows = selectList(Wrappers.<MomentLike>lambdaQuery()
                .select(MomentLike::getMomentId)
                .eq(MomentLike::getUserId, userId)
                .in(MomentLike::getMomentId, momentIds));
        return rows.stream().map(MomentLike::getMomentId).toList();
    }

    /** 赞过的总人数，供详情页与后台展示。 */
    default long countByMoment(Long momentId) {
        return selectCount(Wrappers.<MomentLike>lambdaQuery()
                .eq(MomentLike::getMomentId, momentId));
    }

    /** 我赞过的动态 ID，按点赞时间倒序取前若干条，给「我的点赞」列表用。 */
    default List<Long> selectLikedMoments(Long userId, int limit) {
        int safeLimit = Math.max(1, limit);
        List<MomentLike> rows = selectList(Wrappers.<MomentLike>lambdaQuery()
                .select(MomentLike::getMomentId)
                .eq(MomentLike::getUserId, userId)
                .orderByDesc(MomentLike::getCreateTime)
                .orderByDesc(MomentLike::getId)
                .last("LIMIT " + safeLimit));
        return rows.stream().map(MomentLike::getMomentId).toList();
    }

    /**
     * 取消点赞：物理删一行。
     *
     * @return 影响行数，0 表示本来就没赞过（重复请求或前端状态过期）
     */
    default int deleteBy(Long momentId, Long userId) {
        return delete(Wrappers.<MomentLike>lambdaQuery()
                .eq(MomentLike::getMomentId, momentId)
                .eq(MomentLike::getUserId, userId));
    }

    /** 删动态时清掉它的全部点赞行，避免留下指向空气的记录。 */
    default int deleteByMomentId(Long momentId) {
        return delete(Wrappers.<MomentLike>lambdaQuery()
                .eq(MomentLike::getMomentId, momentId));
    }
}
