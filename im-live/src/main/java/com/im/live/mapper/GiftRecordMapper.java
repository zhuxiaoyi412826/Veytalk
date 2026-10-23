package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.dto.po.GiftAggPO;
import com.im.live.entity.GiftRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 送礼流水 Mapper。
 *
 * <p>插入是送礼主链路（一条流水即一笔账）；下面四个聚合查询服务于「主播数据面板」，
 * 都是只读、只碰既有列，所以<b>零 DDL</b>：
 * <ul>
 *   <li>{@link #aggregateByRooms} 走 {@code idx_room_time} 的 {@code room_id} 前缀，
 *       调用方一次传一页房间（面板封顶 100 场），不逐场问；</li>
 *   <li>{@link #selectTopGifters}、{@link #selectTopGiftTypes} 与 {@link #countSenders}
 *       走 {@code idx_receiver_time} 的 {@code receiver_id} 前缀 + 时间窗，聚合规模是「这个主播收过的流水条数」，
 *       与全站送礼量无关。</li>
 * </ul>
 *
 * <p>没有为面板做汇总表或物化视图：单人项目里那张汇总表要么靠定时任务刷（多一份调度与
 * 不一致窗口），要么写事务里同步刷（把送礼链路弄重）。当前规模下即时聚合够用，
 * 真到需要汇总的那天，代价与口径要一起写进文档。
 */
@Mapper
public interface GiftRecordMapper extends BaseMapper<GiftRecord> {

    /**
     * 按房间聚合礼物流水（一场一行）。
     *
     * <p>空集合必须由调用方挡掉：{@code IN ()} 是语法错误，MyBatis 不会替我们兜。
     *
     * @param roomIds 房间 ID 集合，非空
     * @return 每个<b>有送礼记录</b>的房间一行；没收过礼的房间不会出现在结果里（调用方按缺行当 0 处理）
     */
    @Select("""
            <script>
            SELECT room_id              AS roomId,
                   SUM(total_price)     AS totalPrice,
                   COUNT(*)             AS recordCount,
                   COUNT(DISTINCT sender_id) AS senderCount
            FROM im_gift_record
            WHERE room_id IN
            <foreach collection="roomIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            GROUP BY room_id
            </script>
            """)
    List<GiftAggPO.Room> aggregateByRooms(@Param("roomIds") Collection<Long> roomIds);

    /**
     * 金主榜：某主播在时间窗内收到礼物的按送礼人聚合，按总价值降序。
     *
     * @param receiverId 主播用户 ID
     * @param since      时间窗起点（含），为空表示不限
     * @param limit      取前几名（调用方夹住上限，别让人把全表 group by 出来）
     */
    @Select("""
            <script>
            SELECT sender_id            AS senderId,
                   SUM(total_price)     AS totalPrice,
                   COUNT(*)             AS recordCount,
                   MAX(create_time)     AS lastAt
            FROM im_gift_record
            WHERE receiver_id = #{receiverId}
            <if test="since != null">AND create_time &gt;= #{since}</if>
            GROUP BY sender_id
            ORDER BY totalPrice DESC, recordCount DESC
            LIMIT #{limit}
            </script>
            """)
    List<GiftAggPO.Gifter> selectTopGifters(@Param("receiverId") Long receiverId,
                                            @Param("since") LocalDateTime since,
                                            @Param("limit") int limit);

    /**
     * 礼物种类榜：某主播在时间窗内收到最多的礼物（按累计价值降序）。
     *
     * <p>{@code name / icon} 取的是流水里的冗余列而非 {@code im_gift} 当前值——礼物可能已下架或改名，
     * 面板要还原「当时收到的是什么」。{@code GROUP BY} 带上这三列是为了在
     * {@code ONLY_FULL_GROUP_BY} 下合法（{@code im_gift} 未参与 join，没有跨表依赖）。
     */
    @Select("""
            <script>
            SELECT gift_id              AS giftId,
                   gift_name            AS giftName,
                   gift_icon            AS giftIcon,
                   SUM(`count`)         AS totalCount,
                   SUM(total_price)     AS totalPrice
            FROM im_gift_record
            WHERE receiver_id = #{receiverId}
            <if test="since != null">AND create_time &gt;= #{since}</if>
            GROUP BY gift_id, gift_name, gift_icon
            ORDER BY totalPrice DESC, totalCount DESC
            LIMIT #{limit}
            </script>
            """)
    List<GiftAggPO.Type> selectTopGiftTypes(@Param("receiverId") Long receiverId,
                                            @Param("since") LocalDateTime since,
                                            @Param("limit") int limit);

    /**
     * 送礼人数（去重）：面板概览那一个「多少人给你送过礼」的指标。
     *
     * <p>单独一条而不从金主榜行数推：榜只取前 N 名，人数可能远超 N。
     */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT sender_id)
            FROM im_gift_record
            WHERE receiver_id = #{receiverId}
            <if test="since != null">AND create_time &gt;= #{since}</if>
            </script>
            """)
    Long countSenders(@Param("receiverId") Long receiverId, @Param("since") LocalDateTime since);
}
