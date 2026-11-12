package com.im.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.im.ai.entity.AiUsage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * AI 用量流水 Mapper（AI 护栏 · 四期第六批）。
 *
 * <p>无 GROUP BY 的聚合（概览单行、烧钱榜）走 {@code selectMaps} + QueryWrapper 在调用方拼；
 * 但<b>按人聚合的翻页</b>必须手写 SQL：MP 把 {@code selectPage} 的结果集往实体映射时，
 * 没有对应字段的聚合别名会被静默丢掉（全变 0），而 {@code List<Map>} 返回型不参与
 * 自动 count 推导，正好避开 GROUP BY 的 count 假问题。
 */
public interface AiUsageMapper extends BaseMapper<AiUsage> {

    /** 按人聚合翻页（MP 分页插件接 IPage 参数），累计扣预算耗时倒序 */
    @Select("""
            <script>
            SELECT user_id,
                   COUNT(*) AS total_calls,
                   COALESCE(SUM(success), 0) AS success_calls,
                   COALESCE(SUM(CASE WHEN success = 1 THEN duration_ms ELSE 0 END), 0) AS total_ms,
                   COALESCE(SUM(CASE WHEN create_time >= CURDATE() THEN duration_ms ELSE 0 END), 0) AS today_ms,
                   MAX(create_time) AS last_used_time
            FROM im_ai_usage
            <where>
              <if test="userId != null">AND user_id = #{userId}</if>
              <if test="bannedIds != null">AND user_id IN
                <foreach collection="bannedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
            </where>
            GROUP BY user_id
            ORDER BY total_ms DESC, user_id DESC
            </script>
            """)
    IPage<Map<String, Object>> pageUserAgg(IPage<Map<String, Object>> page,
                                           @Param("userId") Long userId,
                                           @Param("bannedIds") List<Long> bannedIds);

    /** 去重使用人数（上面聚合翻页的总数），与聚合的 WHERE 口径一致 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT user_id) FROM im_ai_usage
            <where>
              <if test="userId != null">AND user_id = #{userId}</if>
              <if test="bannedIds != null">AND user_id IN
                <foreach collection="bannedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
            </where>
            </script>
            """)
    Long countDistinctUsers(@Param("userId") Long userId, @Param("bannedIds") List<Long> bannedIds);
}
