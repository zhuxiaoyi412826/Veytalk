package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.Intimacy;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 亲密度 Mapper。
 *
 * <p>两条写入都是 {@code INSERT ... ON DUPLICATE KEY UPDATE}（与 {@code MatchExposureMapper#upsertBatch}
 * 同一手法）：这一行有没有存在过，取决于这两个人以前有没有来往过，而调用方既不知道也不该先查一次——
 * 「先 SELECT 再决定 INSERT 还是 UPDATE」在两个人同时发消息时会撞唯一键。
 *
 * <p>入参里的 {@code userAId} / {@code userBId} <b>必须已经排好序</b>（a &lt; b），
 * 归一化由 {@code IntimacyServiceImpl} 负责，与 {@code MatchPairMapper} 的 {@code lowId/highId} 同一约定。
 */
@Mapper
public interface IntimacyMapper extends BaseMapper<Intimacy> {

    /** 一对人在表里只可能有一行，所以这是查唯一键，不需要 LIMIT 1。 */
    default Intimacy selectByUsers(Long userAId, Long userBId) {
        if (userAId == null || userBId == null) {
            return null;
        }
        return selectOne(Wrappers.<Intimacy>lambdaQuery()
                .eq(Intimacy::getUserAId, userAId)
                .eq(Intimacy::getUserBId, userBId));
    }

    /**
     * 一条单聊消息落库后推进这一行。
     *
     * <p>火花的三档判定全在这一句 CASE 里：
     * <ul>
     *   <li>{@code last_chat_date = today}：今天已经聊过了，天数不动（同一天发 50 条也只算一天）；</li>
     *   <li>{@code last_chat_date = yesterday}：昨天聊过，续上 +1；</li>
     *   <li>其余（含 NULL 与断过几天）：重新从 1 起算。</li>
     * </ul>
     *
     * <p>★ {@code max_streak} 那一句<b>必须排在 {@code streak_days} 之后</b>：MySQL 的 UPDATE 赋值
     * 是从左到右求值的，写在后面才拿得到刚算出来的新天数；挪到前面就会永远记着上一次的峰值。
     *
     * @param id         雪花主键，自定义 SQL 拿不到 MP 的自动填充，由服务层生成
     * @param scoreDelta 这一条消息加的分（{@link Intimacy#SCORE_PER_MESSAGE}）
     * @param today      服务端当天（自然日的唯一权威时钟，不采信客户端时间）
     */
    @Insert("""
            INSERT INTO im_intimacy (id, user_a_id, user_b_id, score, streak_days, max_streak,
                                     last_chat_date, msg_count, interact_count)
            VALUES (#{id}, #{userAId}, #{userBId}, #{scoreDelta}, 1, 1, #{today}, 1, 0)
            ON DUPLICATE KEY UPDATE
                msg_count = msg_count + 1,
                score = LEAST(score + #{scoreDelta}, #{scoreMax}),
                streak_days = CASE
                    WHEN last_chat_date = #{today} THEN streak_days
                    WHEN last_chat_date = #{yesterday} THEN streak_days + 1
                    ELSE 1
                END,
                max_streak = GREATEST(max_streak, streak_days),
                last_chat_date = #{today}
            """)
    int upsertOnMessage(@Param("id") Long id,
                        @Param("userAId") Long userAId,
                        @Param("userBId") Long userBId,
                        @Param("scoreDelta") int scoreDelta,
                        @Param("scoreMax") int scoreMax,
                        @Param("today") LocalDate today,
                        @Param("yesterday") LocalDate yesterday);

    /**
     * 一次动态互动（点赞 / 评论）后推进这一行。
     *
     * <p><b>不碰 {@code streak_days} 与 {@code last_chat_date}</b>：互动不是说话，
     * 让它推进火花就能靠每天点一个赞刷出永不熄灭的 30 天（口径见 {@code IntimacySpi#onMomentLike}）。
     *
     * <p>新行时火花三列写成 0 / 0 / NULL 而不是 1：这一行可能只是「我赞过 TA 的动态」，
     * 两个人一句话都没说过，此时显示「连续 1 天」是编的。
     */
    @Insert("""
            INSERT INTO im_intimacy (id, user_a_id, user_b_id, score, streak_days, max_streak,
                                     last_chat_date, msg_count, interact_count)
            VALUES (#{id}, #{userAId}, #{userBId}, #{scoreDelta}, 0, 0, NULL, 0, 1)
            ON DUPLICATE KEY UPDATE
                interact_count = interact_count + 1,
                score = LEAST(score + #{scoreDelta}, #{scoreMax})
            """)
    int upsertOnInteract(@Param("id") Long id,
                         @Param("userAId") Long userAId,
                         @Param("userBId") Long userBId,
                         @Param("scoreDelta") int scoreDelta,
                         @Param("scoreMax") int scoreMax);
}
