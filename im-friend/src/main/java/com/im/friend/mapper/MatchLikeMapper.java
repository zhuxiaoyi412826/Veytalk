package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.enums.MatchAction;
import com.im.friend.entity.MatchLike;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 滑动记录 Mapper。
 *
 * <p>便捷查询以 {@code default} 方法内聚在此，Service 层不拼 wrapper（同 {@code FriendMapper} 的约定）。
 *
 * <p>{@code uk_from_to} 保证一个方向只有一行，所以这里所有单行查询都可以直接 {@code selectOne}
 * 而不需要 {@code LIMIT 1} 兜底；只有 {@link #selectSlidTargets} 这类聚合读法带 limit。
 */
@Mapper
public interface MatchLikeMapper extends BaseMapper<MatchLike> {

    /**
     * 「已划过」排除名单最多取多少行。
     *
     * <p>不跨模块 join（{@code im_match_like} 归 im-friend，{@code im_user_profile} 归 im-user），
     * 只能把 ID 列表传给候选池查询。取最近 1000 行的代价是：滑过超过 1000 人的极端账号
     * 会重新看到自己最老的那几张卡，而这比「每次都拉全量 ID 拼进 NOT IN」便宜得多。
     */
    int SLID_EXCLUDE_MAX = 1000;

    /** 某个方向的滑动行；没滑过返回 {@code null}。 */
    default MatchLike selectByDirection(Long fromUserId, Long toUserId) {
        return selectOne(Wrappers.<MatchLike>lambdaQuery()
                .eq(MatchLike::getFromUserId, fromUserId)
                .eq(MatchLike::getToUserId, toUserId));
    }

    /**
     * 我划过哪些人，按最近一次动作时间倒序。
     *
     * <p>默认（{@code action = null}）动作 1 与 2 都在这里：候选池要过滤的是「我已经表过态」，
     * 与表态的方向无关。只取 {@code to_user_id} 一列，给候选池当排除名单用。
     *
     * @param action 只看这一种动作；为 {@code null} 就是两种都算。偏好里
     *               {@code exclude_passed = 0} 的人靠这一参把「跳过」从排除名单里摘出去，
     *               而「喜欢过」仍然排除——否则一个未被回应的喜欢会把同一个人反复推回来
     */
    default List<Long> selectSlidTargets(Long fromUserId, int limit, MatchAction action) {
        int safeLimit = Math.max(1, Math.min(limit, SLID_EXCLUDE_MAX));
        Integer actionCode = action == null ? null : action.getCode();
        List<MatchLike> rows = selectList(Wrappers.<MatchLike>lambdaQuery()
                .select(MatchLike::getToUserId)
                .eq(MatchLike::getFromUserId, fromUserId)
                .eq(actionCode != null, MatchLike::getAction, actionCode)
                .orderByDesc(MatchLike::getUpdateTime)
                .last("LIMIT " + safeLimit));
        return rows.stream().map(MatchLike::getToUserId).toList();
    }

    /**
     * 一批人里我已经喜欢过哪些（{@code action=1}）。
     *
     * <p>「喜欢我的」列表据此把「回个喜欢就能成对」和「还得先等 TA 反悔」区分开。
     */
    default List<Long> selectLikedTargets(Long fromUserId, Collection<Long> toUserIds) {
        if (toUserIds == null || toUserIds.isEmpty()) {
            return List.of();
        }
        List<MatchLike> rows = selectList(Wrappers.<MatchLike>lambdaQuery()
                .select(MatchLike::getToUserId)
                .eq(MatchLike::getFromUserId, fromUserId)
                .eq(MatchLike::getAction, MatchAction.LIKE.getCode())
                .in(MatchLike::getToUserId, toUserIds));
        return rows.stream().map(MatchLike::getToUserId).toList();
    }

    /**
     * 谁喜欢我（只含 {@code action=1}），按对方最近一次改动作的时间倒序取前若干人。
     *
     * <p>与 {@link #selectReceivedLikes} 同一个口径，只是不分页、只要 ID：这一批人是推荐流的
     * <b>互惠信号</b>（喜欢过我的人置顶），所以取完就要截断，不需要总数也不需要页码。
     *
     * <p>SQL 里不滤掉「我已经回过喜欢的人」：那份名单（{@link #selectSlidTargets}）取回来就在内存里，
     * 拼一句 {@code NOT IN (上千个 ID)} 进 SQL 不划算，而调用方本来也要拿它做候选池的排除名单
     * （{@code MatchServiceImpl#deck} 顺手在内存里筛一次）。
     */
    default List<Long> selectLikers(Long toUserId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, SLID_EXCLUDE_MAX));
        List<MatchLike> rows = selectList(Wrappers.<MatchLike>lambdaQuery()
                .select(MatchLike::getFromUserId)
                .eq(MatchLike::getToUserId, toUserId)
                .eq(MatchLike::getAction, MatchAction.LIKE.getCode())
                .orderByDesc(MatchLike::getUpdateTime)
                .last("LIMIT " + safeLimit));
        return rows.stream().map(MatchLike::getFromUserId).toList();
    }

    /**
     * 谁喜欢我：只含 {@code action=1} 的行，按最近一次动作时间倒序。
     *
     * <p>被对方改成跳过（撤销喜欢）的行会自动从这里消失，这正是 action 改值而不是删行的收益：
     * 撤销不需要第二张表记账。
     */
    default IPage<MatchLike> selectReceivedLikes(IPage<MatchLike> page, Long toUserId) {
        return selectPage(page, Wrappers.<MatchLike>lambdaQuery()
                .eq(MatchLike::getToUserId, toUserId)
                .eq(MatchLike::getAction, MatchAction.LIKE.getCode())
                .orderByDesc(MatchLike::getUpdateTime)
                .orderByDesc(MatchLike::getId));
    }
}
