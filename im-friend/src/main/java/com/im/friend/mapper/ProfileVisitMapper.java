package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.ProfileVisit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 访客记录 Mapper。
 *
 * <p>便捷查询以 {@code default} 方法内聚在此，Service 层不拼 wrapper（同 {@link MatchLikeMapper} 的约定）。
 */
@Mapper
public interface ProfileVisitMapper extends BaseMapper<ProfileVisit> {

    /**
     * 一次窗口扫描最多取多少<b>行</b>（不是人数）。
     *
     * <p>一个人一天一行，所以 30 天窗口里最活跃的访客最多能占 30 行；取 200 行再在内存里按人聚合，
     * 足够凑满 {@link #VISITOR_MAX} 位，而且这一步是纯索引读（{@code idx_visited_last}）。
     */
    int WINDOW_SCAN_MAX = 200;

    /** 访客列表一次最多回多少位：这一屏是一屏滚动的「最近来访」，不是能翻到第 N 页的表格 */
    int VISITOR_MAX = 50;

    /**
     * {@code since} 那天之后来看过我的人，按当天最近一次访问时间倒序。
     *
     * <p>排序列与 {@code visited_id} 一起正好覆盖 {@code idx_visited_last(visited_id, last_time)}，
     * 不产生文件排序。同一访客的多行会分散在结果里，聚合在 Service 做（跨天的事 SQL 一句 GROUP BY
     * 是能做，但分页的 total 就会变成「行数」而不是「人数」而需要一个子查询计数——为一个短列表不值）。
     */
    default List<ProfileVisit> selectRecentVisits(Long visitedId, LocalDate since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, WINDOW_SCAN_MAX));
        return selectList(Wrappers.<ProfileVisit>lambdaQuery()
                .eq(ProfileVisit::getVisitedId, visitedId)
                .ge(ProfileVisit::getVisitDate, since)
                .orderByDesc(ProfileVisit::getLastTime)
                .last("LIMIT " + safeLimit));
    }

    /**
     * 记一次来访：命中唯一键 {@code uk_visitor_visited_date} 就累加次数并把最近时间推后。
     *
     * <p>用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查再决定插还是改」：
     * 每一次看资料都要落一笔，先查后写在这里就是白多一次往返（同 {@code MatchExposureMapper#upsertBatch}）。
     *
     * <p>{@code last_time} 写 {@code NOW()} 而不是应用侧的时间：「最近」这个判断只该有一个权威时钟。
     * {@code first_time} 与 {@code update_time} 都不写，交给建表的 {@code DEFAULT CURRENT_TIMESTAMP}
     * 与 {@code ON UPDATE CURRENT_TIMESTAMP}。
     *
     * <p>主键由调用方通过 {@code IdWorker} 生成：{@code im_profile_visit.id} 不是自增列，
     * 而 MyBatis-Plus 的自动填充只对它自己的 {@code insert} 方法生效。
     *
     * @return 受影响行数；MySQL 对「命中已有行且值确实变了」计 2，所以这个数不等于来访次数
     */
    @Insert("""
            INSERT INTO im_profile_visit (id, visitor_id, visited_id, visit_date, view_type)
            VALUES (#{record.id}, #{record.visitorId}, #{record.visitedId}, #{record.visitDate}, #{record.viewType})
            ON DUPLICATE KEY UPDATE
                times     = times + 1,
                view_type = #{record.viewType},
                last_time = NOW()
            """)
    int upsertVisit(@Param("record") ProfileVisit record);
}
