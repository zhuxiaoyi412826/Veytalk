package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.dto.po.ReportAggPO;
import com.im.admin.entity.Report;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 举报工单 Mapper。
 *
 * <p>增删查改走 {@code BaseMapper} 的通用方法（条件查询用 LambdaQueryWrapper，不写 SQL），
 * 这里只写三条 GROUP BY：趋势、理由分布、被举报人榜——它们都是「按维度收拢」的读，
 * 通用 API 拼不出来，而 {@code im_report} 的四个索引刚好各对上一条。
 *
 * <p>看板那几个 {@code COUNT} 反而不放在这里：{@code selectCount(wrapper)} 加一个
 * 时间范围条件就够，为它写 SQL 只会多一处要和表结构同步的地方。
 */
public interface ReportMapper extends BaseMapper<Report> {

    /**
     * 按自然日统计举报量，用于看板的近 7 天趋势。
     *
     * <p>没有日期行的那天不会出现在结果里（SQL 不会补零），补零由 service 做——
     * 让数据库关心「日历」是把它当报表引擎用，而趋势只有七天，Java 侧补更便宜也更直白。
     */
    @Select("""
            SELECT DATE(create_time) AS day,
                   COUNT(*)          AS `count`
            FROM im_report
            WHERE create_time >= #{since}
            GROUP BY DATE(create_time)
            ORDER BY day
            """)
    List<ReportAggPO.Day> aggregateByDay(@Param("since") LocalDateTime since);

    /**
     * 按理由码统计，用于看板的理由分布。
     *
     * <p>只 {@code GROUP BY reason_code}：码串与展示文案的映射在 {@code ReportReason} 里，
     * 不在库里存一份中文，否则改文案要刷历史工单。
     */
    @Select("""
            SELECT reason_code AS reasonCode,
                   COUNT(*)    AS `count`
            FROM im_report
            WHERE create_time >= #{since}
            GROUP BY reason_code
            ORDER BY `count` DESC
            """)
    List<ReportAggPO.Reason> aggregateByReason(@Param("since") LocalDateTime since);

    /**
     * 被举报次数榜：窗口内谁被投诉得最多。
     *
     * <p>过滤掉 {@code reported_user_id IS NULL}：反查不到责任人的工单（对象已删且证据里没留）
     * 不该聚到某个陌生人头上，它们只在队列里按条处理。
     */
    @Select("""
            SELECT reported_user_id AS userId,
                   COUNT(*)         AS `count`
            FROM im_report
            WHERE reported_user_id IS NOT NULL
              AND create_time >= #{since}
            GROUP BY reported_user_id
            ORDER BY `count` DESC
            LIMIT #{limit}
            """)
    List<ReportAggPO.UserCount> aggregateTopReportedUsers(@Param("since") LocalDateTime since,
                                                          @Param("limit") int limit);

    /**
     * 批量取「这几个用户各被举报了多少次」，给用户列表加一列用，避免逐行查。
     *
     * <p>{@code <script>} 是注解式动态 SQL 的必须包裹；空集合必须在 service 侧挡掉，
     * 否则 {@code IN ()} 是语法错。只统计未处置与已受理的工单（终态的不该再算进「当前风险」）。
     */
    @Select("""
            <script>
            SELECT reported_user_id AS userId,
                   COUNT(*)         AS `count`
            FROM im_report
            WHERE status IN (0, 1)
              AND reported_user_id IN
            <foreach collection="userIds" item="uid" open="(" separator="," close=")">#{uid}</foreach>
            GROUP BY reported_user_id
            </script>
            """)
    List<ReportAggPO.UserCount> countPendingByUsers(@Param("userIds") Collection<Long> userIds);
}
