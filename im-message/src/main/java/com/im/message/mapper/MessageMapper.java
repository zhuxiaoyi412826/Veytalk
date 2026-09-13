package com.im.message.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.message.dto.po.ConversationMaxSeq;
import com.im.message.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 消息 Mapper。
 *
 * <p>历史消息一律用 {@code seq} 游标而不是页码：聊天记录的总量只增不减，
 * {@code LIMIT 100000, 20} 这种深翻页在数据量上来后会直接拖垮数据库，
 * 而 {@code WHERE conversation_id = ? AND seq &lt; ? ORDER BY seq DESC LIMIT 20}
 * 正好命中 {@code idx_conv_seq} 索引，翻到多深都是常数代价。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 待处理已读消息的单次上限，防止用户长期未读后一次上报拖出海量数据。
     */
    int READ_BATCH_LIMIT = 500;

    /**
     * 幂等回查：同一发送者的同一 clientMsgId 只会有一行（唯一键 {@code uk_from_client}）。
     */
    default Message selectByClientMsgId(Long fromUserId, String clientMsgId) {
        return selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getFromUserId, fromUserId)
                .eq(Message::getClientMsgId, clientMsgId)
                .last("LIMIT 1"));
    }

    /**
     * 会话当前最大 seq，用于 Redis 计数器缺失时校正初值，以及 Redis 不可用时的降级发号。
     *
     * @return 会话还没有消息时返回 0
     */
    @Select("""
            SELECT COALESCE(MAX(seq), 0)
            FROM im_message
            WHERE conversation_id = #{conversationId}
            """)
    long selectMaxSeq(@Param("conversationId") Long conversationId);

    /**
     * 游标分页取历史消息：倒序截取一页，调用方再反转成正序返回给前端。
     *
     * @param beforeSeq 游标，为空表示从最新一条开始
     * @param limit     本页条数
     */
    default List<Message> selectHistory(Long conversationId, Long beforeSeq, long limit) {
        LambdaQueryWrapper<Message> wrapper = Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .lt(beforeSeq != null, Message::getSeq, beforeSeq)
                .orderByDesc(Message::getSeq);
        Page<Message> page = Page.of(1, limit);
        // 聊天记录不需要总条数，关掉 COUNT 少一次全表扫描
        page.setSearchCount(false);
        return selectPage(page, wrapper).getRecords();
    }

    /**
     * 取会话内 seq 大于指定位点的消息（离线消息），按 seq 升序，最多 limit 条。
     */
    default List<Message> selectAfterSeq(Long conversationId, long afterSeq, long limit) {
        LambdaQueryWrapper<Message> wrapper = Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .gt(Message::getSeq, afterSeq)
                .orderByAsc(Message::getSeq);
        Page<Message> page = Page.of(1, limit);
        page.setSearchCount(false);
        return selectPage(page, wrapper).getRecords();
    }

    /**
     * 查出某用户在某会话上「别人发的、还没标记过已读」的消息，按 seq 倒序截断。
     *
     * <p>用 {@code NOT EXISTS} 在数据库端过滤掉已有已读回执的行，避免把全量消息捞回内存再比对；
     * 同时排除已撤回的消息——撤回消息不需要回执，前端也不再展示它。
     *
     * @param maxSeq 已读到的位点，调用方保证非空（整会话已读时传一个足够大的值）
     */
    @Select("""
            SELECT m.id, m.conversation_id, m.from_user_id, m.seq
            FROM im_message m
            WHERE m.conversation_id = #{conversationId}
              AND m.from_user_id <> #{userId}
              AND m.is_recalled = 0
              AND m.seq <= #{maxSeq}
              AND NOT EXISTS (SELECT 1
                              FROM im_message_read r
                              WHERE r.message_id = m.id
                                AND r.user_id = #{userId}
                                AND r.read_time IS NOT NULL)
            ORDER BY m.seq DESC
            LIMIT #{limit}
            """)
    List<Message> selectPendingRead(@Param("conversationId") Long conversationId,
                                    @Param("userId") Long userId,
                                    @Param("maxSeq") Long maxSeq,
                                    @Param("limit") int limit);

    /**
     * 撤回消息，条件里带 {@code is_recalled = 0}，兼作并发防护：
     * 两个人同时撤回同一条消息时只有一个能成功。
     *
     * @return 影响行数为 0 表示已被撤回过
     */
    @Update("""
            UPDATE im_message
            SET is_recalled = 1,
                recall_time = #{recallTime}
            WHERE id = #{messageId}
              AND is_recalled = 0
            """)
    int markRecalled(@Param("messageId") Long messageId, @Param("recallTime") LocalDateTime recallTime);

    /**
     * 一次查出多个会话各自的未拉取消息 ID，按会话与 seq 升序，总数最多 limit 条。
     *
     * <p>入参是「会话 ID -&gt; 已确认位点」的映射，用 {@code UNION ALL} 把它摊成一张两列的派生表
     * 再与消息表 JOIN，这样无论用户有多少个会话都只有一次数据库往返。
     * 逐个会话调 {@link #selectAfterSeq} 虽然写法简单，但一个有几十个会话的用户上线时会打出几十条 SQL。
     *
     * <p>只返回 ID 而不返回实体：自定义 SQL 不会走 {@code autoResultMap}，
     * {@code extra} JSON 列拿不到 TypeHandler 只会得到一个原始字符串。
     * 因此这里先取 ID，再由 {@link #selectByIdIn} 用 MyBatis-Plus 内置方法把完整实体查出来。
     *
     * @param positions key 为 conversationId，value 为已确认位点，调用方保证两者均非空
     */
    @Select("""
            <script>
                SELECT m.id
                FROM im_message m
                INNER JOIN (
                    <foreach collection="positions" index="conversationId" item="ackSeq" separator=" UNION ALL ">
                        SELECT #{conversationId,jdbcType=BIGINT} AS conversation_id,
                               #{ackSeq,jdbcType=BIGINT}         AS ack_seq
                    </foreach>
                ) t ON t.conversation_id = m.conversation_id AND m.seq &gt; t.ack_seq
                ORDER BY m.conversation_id, m.seq
                LIMIT #{limit}
            </script>
            """)
    List<Long> selectOfflineIds(@Param("positions") Map<Long, Long> positions, @Param("limit") int limit);

    /**
     * 批量取多个会话的当前最大 seq，用于清除离线标记时判断哪些会话需要推进位点。
     *
     * <p>没有任何消息的会话不会出现在结果里，调用方据此可跳过无意义的更新。
     */
    @Select("""
            <script>
                SELECT conversation_id, MAX(seq) AS max_seq
                FROM im_message
                WHERE conversation_id IN
                <foreach collection="conversationIds" item="id" open="(" separator="," close=")">
                    #{id}
                </foreach>
                GROUP BY conversation_id
            </script>
            """)
    List<ConversationMaxSeq> selectMaxSeqBatch(@Param("conversationIds") Collection<Long> conversationIds);

    /**
     * 批量按 ID 查询，用于组装 VO 时补齐发送者资料前的取数。
     *
     * <p>排序带上 conversation_id 是为了同时满足两种调用场景：历史分页只有一个会话，
     * 按 seq 升序即可；离线拉取横跨多个会话，必须先按会话聚拢再按 seq 升序，
     * 否则前端按会话分组时会出现消息乱序插入。
     */
    default List<Message> selectByIdIn(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Message>lambdaQuery()
                .in(Message::getId, messageIds)
                .orderByAsc(Message::getConversationId)
                .orderByAsc(Message::getSeq));
    }

    /**
     * 消息内容检索，按会话过滤，走分页插件。
     *
     * <p>{@code LIKE '%kw%'} 无法使用索引，这里限定必须先选会话再搜索，
     * 把扫描范围收敛到单个会话内，避免全站消息表模糊查询。
     */
    default Page<Message> searchByKeyword(Page<Message> page, Long conversationId, String keyword) {
        return selectPage(page, Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getRecalled, 0)
                .like(Message::getContent, keyword)
                .orderByDesc(Message::getSeq));
    }

    /**
     * 判断查看者能否看到引用了指定文件的附件消息，文件下载鉴权的唯一依据。
     *
     * <p>驱动方向刻意选成「上传者的消息」而不是「查看者的会话」：
     * 前者能用 {@code uk_from_client} 的 {@code from_user_id} 前缀定位到人，
     * 再对每一行做一次 {@code uk_conv_user} 唯一键点查，代价只与上传者发过多少消息相关；
     * 反过来从 {@code im_conversation_member} 驱动的话，得把查看者名下每个会话的全部消息
     * 扫一遍才能判定「看不到」，重度用户的会话历史动辄上万条，下载一张图就变成一次全库扰动。
     *
     * <p>{@code msg_type} 限定为图片 / 文件 / 语音（对应 {@code MsgType.IMAGE/FILE/VOICE}）：
     * 文本消息的 {@code content} 是正文，完全可能正好是一串与文件 ID 相同的数字，
     * 不限定类型就会把「群里有人发过“12345”这句话」当成“他能看到 12345 号文件”。
     *
     * @param fileId 文件 ID，附件消息的 {@code content} 存的就是它的字符串形式
     */
    @Select("""
            SELECT EXISTS(
                SELECT 1
                FROM im_message m
                WHERE m.from_user_id = #{uploaderId}
                  AND m.msg_type IN (2, 3, 4)
                  AND m.content = #{fileId}
                  AND m.is_recalled = 0
                  AND EXISTS (SELECT 1
                              FROM im_conversation_member cm
                              WHERE cm.conversation_id = m.conversation_id
                                AND cm.user_id = #{viewerId})
            )
            """)
    boolean existsVisibleFileMessage(@Param("fileId") String fileId,
                                     @Param("uploaderId") Long uploaderId,
                                     @Param("viewerId") Long viewerId);
}
