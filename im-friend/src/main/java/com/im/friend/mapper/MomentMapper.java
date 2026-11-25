package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.friend.entity.Moment;
import com.im.friend.entity.MomentMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 动态 Mapper。
 *
 * <p>便捷查询以 {@code default} 方法内聚在此，Service 层不拼 wrapper（同 {@code FriendMapper} 的约定）。
 *
 * <p><b>排序与游标都用 {@code id} 而不用 {@code create_time}</b>：雪花 ID 单调递增，
 * 用它排序等价于按发布时间排序，而游标只需要一列。用 {@code (create_time, id)} 复合游标要写
 * {@code (create_time &lt; ? OR (create_time = ? AND id &lt; ?))}，多出来的那半句在
 * {@code idx_status_time} 上用不上索引；而「同一秒发了两条」在单列游标下天然有确定顺序。
 *
 * <p>唯一的例外是 {@link #selectMinePage}：那是作者按「评论数 / 浏览量」排自己的动态，
 * 排序列不是 {@code id}，所以它额外补一段 {@code ORDER BY id DESC} 做同值时的确定次序，
 * 否则同一条赞数的两条动态在翻页之间会互换位置，出现「第一页末尾和第二页开头是同一条」。
 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {

    /**
     * 一次最多从库里捞多少候选行。
     *
     * <p>候选行还要在内存里过三道闸（可见范围、白名单、三天可见），过完才截成前端要的那几条，
     * 所以这里必须比页大小多取一些。取三倍是一个折中：够填满一屏，又不至于把上千行拉到应用里。
     */
    int CANDIDATE_MAX = 200;

    /**
     * Feed 候选行：一批作者、{@code id} 小于游标、状态正常的动态，按 {@code id} 倒序。
     *
     * <p>{@code authorIds} 是<b>调用方已经算好的受众</b>（我的好友 ∪ 我配对过的人 ∪ 我自己），
     * 这里不再判一次关系：受众的判据在 im-friend 的关系表上，Mapper 只做取数。
     *
     * <p>不在 SQL 里过滤可见范围：{@code visibility} 的每一档判据都不一样
     * （公开看关系、部分人看白名单表、三天可见看作者的偏好列），而作者的偏好列住在 im-user，
     * 跨模块 join 不了。硬塞进 SQL 只会得到一句读不懂的 CASE，所以过滤统一放在服务层。
     *
     * @param cursorId 上一页最后一条的 ID；为 {@code null} 表示第一页
     * @param limit    取多少行，会被夹到 {@link #CANDIDATE_MAX}
     */
    default List<Moment> selectFeedCandidates(Collection<Long> authorIds, Long cursorId, int limit) {
        if (authorIds == null || authorIds.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, CANDIDATE_MAX));
        return selectList(Wrappers.<Moment>lambdaQuery()
                .in(Moment::getUserId, authorIds)
                .eq(Moment::getStatus, Moment.STATUS_NORMAL)
                .lt(cursorId != null, Moment::getId, cursorId)
                .orderByDesc(Moment::getId)
                .last("LIMIT " + safeLimit));
    }

    /**
     * 某个人主页上的动态，按 {@code id} 倒序。
     *
     * <p>{@code includeOffline} 为真时连已下架的一起取：只有作者本人看自己的主页时才为真，
     * 他要能看到「我这条被下架了」，否则只会以为发布失败然后重发一遍。
     *
     * @param since 只要这个时刻之后的行；为 {@code null} 表示不设时间下界（作者本人看自己的全部）
     */
    default List<Moment> selectByUser(Long userId, Long cursorId, int limit,
                                      boolean includeOffline, LocalDateTime since) {
        int safeLimit = Math.max(1, Math.min(limit, CANDIDATE_MAX));
        return selectList(Wrappers.<Moment>lambdaQuery()
                .eq(Moment::getUserId, userId)
                .eq(!includeOffline, Moment::getStatus, Moment.STATUS_NORMAL)
                .lt(cursorId != null, Moment::getId, cursorId)
                .ge(since != null, Moment::getCreateTime, since)
                .orderByDesc(Moment::getId)
                .last("LIMIT " + safeLimit));
    }

    /**
     * 「我的动态」分页的过滤条件（不含作者与分页本身）。
     *
     * <p>收成一个不可变值对象而不是十一个方法参数：这十个条件里大半可以缺省，
     * 列在签名里读不出哪个能传 {@code null}，而调用点也无法对齐。
     *
     * @param contentType     内容形态：{@code null} 不限，1 图文 2 视频 3 纯文字
     * @param status          状态过滤，{@code null} 不限（作者要能看到自己已下架的那几条）
     * @param startTime       发布时间下界（含），{@code null} 不限
     * @param endTime         发布时间上界（不含，由服务层把「结束日期 + 1 天」算好传进来）
     * @param keyword         正文关键字，{@code null} / 空白不限
     * @param minCommentCount 评论数下限（含），{@code null} 不限
     * @param minViewCount    浏览量下限（含），{@code null} 不限
     * @param minLikeCount    点赞数下限（含），{@code null} 不限
     * @param orderColumn     排序列，由服务层按 sortBy 映射成方法引用（不接受列名字符串，那样能被注进来）
     * @param asc             升序还是倒序
     */
    record MineFilter(Integer contentType, Integer status, LocalDateTime startTime, LocalDateTime endTime,
                      String keyword, Integer minCommentCount, Integer minViewCount, Integer minLikeCount,
                      SFunction<Moment, ?> orderColumn, boolean asc) {
    }

    /** 内容形态：图文（有附件且不含视频） */
    int KIND_IMAGE = 1;
    /** 内容形态：视频 */
    int KIND_VIDEO = 2;
    /** 内容形态：纯文字（无附件） */
    int KIND_TEXT = 3;

    /** 判定「这一条带不带视频行」的相关子查询，与 {@link #KIND_VIDEO} / {@link #KIND_IMAGE} 配套。 */
    String HAS_VIDEO_EXISTS = "select 1 from im_moment_media mm where mm.moment_id = im_moment.id"
            + " and mm.type = " + MomentMedia.TYPE_VIDEO + " and mm.deleted = 0";

    /**
     * 我发的动态分页，带形态 / 状态 / 时间区间 / 热度下限四个维度的筛选。
     *
     * <p>与 {@link #selectFeedCandidates} 最大的区别是<b>这里不跑可见范围过滤</b>：作者看自己的
     * 列表不需要判断「谁能看到」，而一旦套上那套闸，「仅自己」的那几条就会从页面上消失，
     * 作者反而以为自己发丢了。{@code status} 也不默认夹成正常：已下架的行要留在结果里，
     * 否则这一屏就成了「我发了十条但只看得见八条」。
     *
     * <p>形态不用子表 join 而是 EXISTS：一条动态要么≤ 9 图要么 1 视频（服务层保证不混排），
     * 所以「是不是视频」是一个存在性判定而不是计数。纯文字直接读冗余列 {@code media_count}，
     * 不碰子表。
     */
    default IPage<Moment> selectMinePage(IPage<Moment> page, Long userId, MineFilter filter) {
        LambdaQueryWrapper<Moment> wrapper = Wrappers.<Moment>lambdaQuery()
                .eq(Moment::getUserId, userId)
                .eq(filter.status() != null, Moment::getStatus, filter.status())
                .ge(filter.startTime() != null, Moment::getCreateTime, filter.startTime())
                .lt(filter.endTime() != null, Moment::getCreateTime, filter.endTime())
                .gt(filter.minCommentCount() != null, Moment::getCommentCount, zeroIfNull(filter.minCommentCount()) - 1)
                .gt(filter.minViewCount() != null, Moment::getViewCount, zeroIfNull(filter.minViewCount()) - 1)
                .gt(filter.minLikeCount() != null, Moment::getLikeCount, zeroIfNull(filter.minLikeCount()) - 1)
                .like(filter.keyword() != null, Moment::getContent, filter.keyword());
        Integer kind = filter.contentType();
        if (kind != null && kind == KIND_VIDEO) {
            wrapper.exists(HAS_VIDEO_EXISTS);
        } else if (kind != null && kind == KIND_IMAGE) {
            wrapper.gt(Moment::getMediaCount, 0).notExists(HAS_VIDEO_EXISTS);
        } else if (kind != null && kind == KIND_TEXT) {
            wrapper.le(Moment::getMediaCount, 0);
        }
        if (filter.asc()) {
            wrapper.orderByAsc(filter.orderColumn());
        } else {
            wrapper.orderByDesc(filter.orderColumn());
        }
        return selectPage(page, wrapper.orderByDesc(Moment::getId));
    }

    /**
     * 「下限（含）」写成 {@code > min - 1}而不是 {@code >= min}：语义相同，但后者在
     * MyBatis-Plus 的条件重载里要传三参，而三参版本跟 {@code gt}/{@code lt} 混排时极易写歪。
     */
    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    /** 一条状态正常的动态；已下架或已删除返回 {@code null}（调用方一律报「不在了」）。 */
    default Moment selectNormalById(Long id) {
        if (id == null) {
            return null;
        }
        return selectOne(Wrappers.<Moment>lambdaQuery()
                .eq(Moment::getId, id)
                .eq(Moment::getStatus, Moment.STATUS_NORMAL));
    }

    /** 一批 ID 里状态正常的那些，给批量渲染与「转发目标还在不在」用。 */
    default List<Moment> selectNormalByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Moment>lambdaQuery()
                .in(Moment::getId, ids)
                .eq(Moment::getStatus, Moment.STATUS_NORMAL));
    }

    /**
     * 增减点赞数。
     *
     * <p>一条 UPDATE 而不是读出来改完再写回：两个人同时点赞会丢掉一次计数。
     * {@code GREATEST(..., 0)} 是必需的——计数与 {@code im_moment_like} 的行数最终一致而非时刻一致，
     * 取消点赞时减成负数会一路显示成「-1 人赞」。
     *
     * <p>自己写 SQL 拿不到 MyBatis-Plus 的逻辑删除过滤，所以 {@code deleted = 0} 要手写在条件里。
     *
     * @param delta +1 点赞，-1 取消
     */
    @Update("""
            UPDATE im_moment
            SET like_count = GREATEST(like_count + #{delta}, 0)
            WHERE id = #{momentId}
              AND deleted = 0
            """)
    int adjustLikeCount(@Param("momentId") Long momentId, @Param("delta") int delta);

    /**
     * 增减评论数，口径同 {@link #adjustLikeCount}。
     *
     * <p>删一条一级评论时传的 delta 是<b>负的整棵子树行数</b>，不是 -1：
     * 一级评论下面的回复也计在 {@code comment_count} 里，只减 1 会让这个数字永远虚高。
     */
    @Update("""
            UPDATE im_moment
            SET comment_count = GREATEST(comment_count + #{delta}, 0)
            WHERE id = #{momentId}
              AND deleted = 0
            """)
    int adjustCommentCount(@Param("momentId") Long momentId, @Param("delta") int delta);

    /**
     * 浏览量 +1（详情页打开时，只对「点开的人不是作者」调）。
     *
     * <p>不做读改写：两个受众同时点开同一条动态，读改写会丢一次增量。这一列不进任何
     * 业务判定（只用于展示与排序），丢一两下只是排序里换个位置，所以不加锁也不建计数表。
     *
     * <p>★ 逻辑删除过滤在这里要手写在条件里（自定义 SQL 拿不到 MyBatis-Plus 的自动拼接）。
     */
    @Update("""
            UPDATE im_moment
            SET view_count = view_count + 1
            WHERE id = #{momentId}
              AND deleted = 0
            """)
    int increaseViewCount(@Param("momentId") Long momentId);

    /**
     * 带状态条件的上下架，兼作乐观锁。
     *
     * @return 影响行数，0 表示状态已被并发请求改走（作者本人删与后台下架同时发生时就是这种情况）
     */
    default int updateStatusIf(Long momentId, int fromStatus, int toStatus) {
        return update(null, Wrappers.<Moment>lambdaUpdate()
                .set(Moment::getStatus, toStatus)
                .eq(Moment::getId, momentId)
                .eq(Moment::getStatus, fromStatus));
    }
}
