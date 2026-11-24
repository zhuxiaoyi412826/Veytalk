package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MomentVisible;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 「部分人可见」白名单 Mapper。
 *
 * <p>这张表<b>没有 {@code deleted} 列</b>，所以 {@link #deleteByMomentId} 是物理删除：
 * 改可见范围时整批重写（先删光再插新的），逻辑删会让第二次插入撞 {@code uk_moment_user}。
 *
 * <p>白名单只对 {@code visibility = 3} 的动态有行。其余三档一行都不写——判据在关系侧，
 * 见 {@code Moment#visibleTo}。
 */
@Mapper
public interface MomentVisibleMapper extends BaseMapper<MomentVisible> {

    /**
     * 一批动态里，{@code userId} 在白名单上的那些 ID。
     *
     * <p>Feed 过滤要靠它：一屏十几条动态里可能有好几条是「部分人可见」，
     * 逐条查白名单就是十几次往返。这里一次 {@code IN} 取完，在服务层做成 {@code Set}。
     */
    default List<Long> selectVisibleMomentIds(Collection<Long> momentIds, Long userId) {
        if (momentIds == null || momentIds.isEmpty() || userId == null) {
            return List.of();
        }
        List<MomentVisible> rows = selectList(Wrappers.<MomentVisible>lambdaQuery()
                .select(MomentVisible::getMomentId)
                .in(MomentVisible::getMomentId, momentIds)
                .eq(MomentVisible::getUserId, userId));
        return rows.stream().map(MomentVisible::getMomentId).toList();
    }

    /** 一条动态的白名单成员，供后台与「谁能看到这条」的回显。 */
    default List<Long> selectUserIds(Long momentId) {
        if (momentId == null) {
            return List.of();
        }
        List<MomentVisible> rows = selectList(Wrappers.<MomentVisible>lambdaQuery()
                .select(MomentVisible::getUserId)
                .eq(MomentVisible::getMomentId, momentId));
        return rows.stream().map(MomentVisible::getUserId).toList();
    }

    /** 物理删光一条动态的白名单。改可见范围与删动态都走这一句。 */
    default int deleteByMomentId(Long momentId) {
        return delete(Wrappers.<MomentVisible>lambdaQuery()
                .eq(MomentVisible::getMomentId, momentId));
    }

    /**
     * 批量写白名单。
     *
     * <p>主键由调用方通过 {@code IdWorker} 生成：这一列不是自增，而 MyBatis-Plus 的自动填充
     * 只对它自己的 {@code insert} 生效（同 {@code MatchExposureMapper#upsertBatch} 的约定）。
     *
     * <p>不做 {@code ON DUPLICATE KEY UPDATE}：写之前一定先 {@link #deleteByMomentId}，
     * 而调用方已经把 ID 去过重，唯一键在这里撞不上；真撞上了说明有人绕过服务层直写，
     * 让它报错比悄悄吞掉好。
     */
    @Insert("""
            <script>
                INSERT INTO im_moment_visible (id, moment_id, user_id)
                VALUES
                <foreach collection="records" item="item" separator=",">
                    (#{item.id}, #{item.momentId}, #{item.userId})
                </foreach>
            </script>
            """)
    int insertBatch(@Param("records") Collection<MomentVisible> records);
}
