package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MomentComment;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 动态评论 Mapper。
 *
 * <p>评论<b>一次取全</b>而不分页：一条动态的评论量在交友场景里是几十条量级，
 * 而两层结构要拼起来才知道「这条回复挂在谁下面」，分页取会让同一棵子树被切到两页里。
 * 真要控量，控制的是 {@link #selectByMomentId} 的 {@code limit}，超出部分前端显示
 * 「还有 N 条评论」，这比让一棵子树断在半空好。
 *
 * <p>排序用 {@code (create_time, id)} 升序：评论是聊天式的顺序读，与动态列表的倒序相反。
 */
@Mapper
public interface MomentCommentMapper extends BaseMapper<MomentComment> {

    /**
     * 一次最多下发多少条评论。
     *
     * <p>这个上限存在的理由是防止一条被刷屏的动态把整屏 Feed 拖垮，
     * 而不是产品上的「只显示最新 N 条」：真被刷屏时该做的是后台下架，不是悄悄藏起来。
     */
    int LIST_MAX = 200;

    /** 一条动态的全部正常评论，按时间升序（一级与其回复混在一起，由服务层按 {@code parentId} 分组）。 */
    default List<MomentComment> selectByMomentId(Long momentId) {
        return selectByMomentId(momentId, LIST_MAX);
    }

    default List<MomentComment> selectByMomentId(Long momentId, int limit) {
        if (momentId == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, LIST_MAX));
        return selectList(Wrappers.<MomentComment>lambdaQuery()
                .eq(MomentComment::getMomentId, momentId)
                .eq(MomentComment::getStatus, MomentComment.STATUS_NORMAL)
                .orderByAsc(MomentComment::getCreateTime)
                .orderByAsc(MomentComment::getId)
                .last("LIMIT " + safeLimit));
    }

    /** 一批动态的评论，供 Feed 每条动态带出前几条评论。 */
    default List<MomentComment> selectByMomentIds(Collection<Long> momentIds, int limitPerMoment) {
        if (momentIds == null || momentIds.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limitPerMoment, LIST_MAX));
        // 每条动态最多带 safeLimit 条：总数上限是「动态条数 × 每动态上限」，
        // 不在这里再夹一次总量——一屏十几条动态，每条两三条评论，本来就只有几十行
        return selectList(Wrappers.<MomentComment>lambdaQuery()
                .in(MomentComment::getMomentId, momentIds)
                .eq(MomentComment::getStatus, MomentComment.STATUS_NORMAL)
                .orderByAsc(MomentComment::getMomentId)
                .orderByAsc(MomentComment::getCreateTime)
                .orderByAsc(MomentComment::getId)
                .last("LIMIT " + (momentIds.size() * safeLimit)));
    }

    /** 一条状态正常的评论；已下架或已删除返回 {@code null}。 */
    default MomentComment selectNormalById(Long id) {
        if (id == null) {
            return null;
        }
        return selectOne(Wrappers.<MomentComment>lambdaQuery()
                .eq(MomentComment::getId, id)
                .eq(MomentComment::getStatus, MomentComment.STATUS_NORMAL));
    }

    /**
     * 一条一级评论下面的全部回复（含已下架的行）。
     *
     * <p>删一级评论时要连回复一起删，而这个数还得回填给 {@code im_moment.comment_count}：
     * 只减 1 会让计数永远虚高，虚高的数字会一路显示到后台的处置记录里。
     */
    default List<MomentComment> selectChildren(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        return selectList(Wrappers.<MomentComment>lambdaQuery()
                .eq(MomentComment::getParentId, parentId));
    }

    /** 逻辑删一条及其全部回复，返回实际删掉的行数。 */
    default int deleteWithChildren(Long commentId) {
        return delete(Wrappers.<MomentComment>lambdaQuery()
                .eq(MomentComment::getId, commentId)
                .or(w -> w.eq(MomentComment::getParentId, commentId)));
    }

    /** 删动态时清掉它的全部评论（逻辑删）。 */
    default int deleteByMomentId(Long momentId) {
        return delete(Wrappers.<MomentComment>lambdaQuery()
                .eq(MomentComment::getMomentId, momentId));
    }

    /** 带状态条件的下架 / 恢复，兼作乐观锁；返回 0 表示状态已被并发请求改走。 */
    default int updateStatusIf(Long commentId, int fromStatus, int toStatus) {
        return update(null, Wrappers.<MomentComment>lambdaUpdate()
                .set(MomentComment::getStatus, toStatus)
                .eq(MomentComment::getId, commentId)
                .eq(MomentComment::getStatus, fromStatus));
    }
}
