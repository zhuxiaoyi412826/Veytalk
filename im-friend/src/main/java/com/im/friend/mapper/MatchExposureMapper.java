package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MatchExposure;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 曝光池 Mapper。
 *
 * <p>便捷查询以 {@code default} 方法内聚在此，Service 层不拼 wrapper（同 {@link MatchLikeMapper} 的约定）。
 *
 * <p>这张表只有两种用法：推荐流取「最近刷到过谁」拿去垫后，以及取完卡池后补一笔曝光。
 * 没有按 {@code expose_count} 的读法——次数目前只用于排查「为什么我看不到新人」；真要按次数
 * 衰减排序，衰减函数得住在 im-user 那一侧（排序口径的唯一出处），而不是现在先写一个没人读的分支。
 */
@Mapper
public interface MatchExposureMapper extends BaseMapper<MatchExposure> {

    /**
     * 垫后名单一次最多取多少人。
     *
     * <p>候选池本身最多只取 50 行，再大的名单就是白传：这一批 ID 只在内存里做分区，不进 SQL。
     */
    int DEPRIORITIZE_MAX = 200;

    /**
     * {@code since} 之后被我刷到过的人，按最近曝光时间<b>倒序</b>。
     *
     * <p>倒序取而不是升序：一个很久没再出现过的人本来就该重新进池了，需要垫后的是「刚见过」的那批；
     * 取满上限就截断，被截掉的正是最老的那些。
     *
     * <p>条件与排序都落在 {@code idx_user_last(user_id, last_expose_time)} 的同一列上，不产生文件排序。
     */
    default List<Long> selectRecentExposed(Long userId, LocalDateTime since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, DEPRIORITIZE_MAX));
        List<MatchExposure> rows = selectList(Wrappers.<MatchExposure>lambdaQuery()
                .select(MatchExposure::getCandidateId)
                .eq(MatchExposure::getUserId, userId)
                .ge(MatchExposure::getLastExposeTime, since)
                .orderByDesc(MatchExposure::getLastExposeTime)
                .last("LIMIT " + safeLimit));
        return rows.stream().map(MatchExposure::getCandidateId).toList();
    }

    /**
     * 记一次批量曝光：命中唯一键 {@code uk_user_candidate} 就累加次数并把曝光时间推到当下。
     *
     * <p>用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查再决定插还是改」：
     * 一发卡池十几到三十条，而这是每次刷新都会走的路径，逐条读写就是两倍以上往返
     * （同 {@code MessageReadMapper#upsertBatch}）。
     *
     * <p>列清单里只有三个业务列：<b>次数、两个时间全交给建表的 {@code DEFAULT}</b>
     * （{@code expose_count DEFAULT 1}、{@code last_expose_time DEFAULT CURRENT_TIMESTAMP}），
     * 命中已有行时用 {@code NOW()} 而不是 Java 侧的时间——「多久算最近」只该有一个权威时钟，
     * 应用与库不在同一时区时，传进来的时间戳会让垫后的判断每天错几小时。
     * {@code update_time} 也不写，由 DDL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 自己跳。
     *
     * <p>主键由调用方通过 {@code IdWorker} 生成：{@code im_match_exposure.id} 不是自增列，
     * 而 MyBatis-Plus 的自动填充只对它自己的 {@code insert} 方法生效，自定义 SQL 需要手工赋值。
     *
     * @return 受影响的行数；MySQL 对「命中已有行且值确实变了」计 2，所以这个数不等于人数
     */
    @Insert("""
            <script>
                INSERT INTO im_match_exposure (id, user_id, candidate_id)
                VALUES
                <foreach collection="records" item="item" separator=",">
                    (#{item.id}, #{item.userId}, #{item.candidateId})
                </foreach>
                ON DUPLICATE KEY UPDATE
                    expose_count     = expose_count + 1,
                    last_expose_time = NOW()
            </script>
            """)
    int upsertBatch(@Param("records") Collection<MatchExposure> records);
}
