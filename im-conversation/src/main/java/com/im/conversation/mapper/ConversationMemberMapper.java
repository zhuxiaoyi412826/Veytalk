package com.im.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.conversation.dto.po.ConversationView;
import com.im.conversation.entity.ConversationMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 会话成员 Mapper。
 *
 * <p>成员表与会话表同属本模块，因此列表查询直接 JOIN，让数据库完成跨表排序，
 * 避免「先查成员再查会话最后在内存里排序」在会话数量增长后的性能塌陷。
 */
@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    /**
     * 会话视图查询的公共 SELECT 片段。
     *
     * <p>{@code is_top} / {@code is_muted} 用别名对齐 {@link ConversationView} 的属性名，
     * 其余列依赖 {@code map-underscore-to-camel-case} 自动映射。
     */
    String VIEW_SELECT = """
            SELECT c.id,
                   c.type,
                   c.target_id,
                   c.last_msg_id,
                   c.last_msg_content,
                   c.last_msg_type,
                   c.last_msg_time,
                   m.unread_count,
                   m.last_ack_seq,
                   m.is_top   AS top,
                   m.top_time,
                   m.is_muted AS muted,
                   m.at_flag
            FROM im_conversation_member m
                     JOIN im_conversation c ON c.id = m.conversation_id AND c.deleted = 0
            """;

    /**
     * 会话列表主查询：过滤本端隐藏的会话，按「置顶 -&gt; 置顶时间 -&gt; 最新消息时间」排序。
     */
    @Select(VIEW_SELECT + """
            WHERE m.user_id = #{userId}
              AND m.is_deleted = 0
            ORDER BY m.is_top DESC, m.top_time DESC, c.last_msg_time DESC, c.id DESC
            """)
    List<ConversationView> selectViewsByUserId(@Param("userId") Long userId);

    /**
     * 单个会话的视角查询，用于会话详情。
     *
     * <p>刻意不限制 {@code m.is_deleted}：本端隐藏的会话仍然可以直接打开，
     * 只是不出现在列表里。
     */
    @Select(VIEW_SELECT + """
            WHERE m.user_id = #{userId}
              AND m.conversation_id = #{conversationId}
            """)
    ConversationView selectView(@Param("userId") Long userId, @Param("conversationId") Long conversationId);

    /**
     * 统计用户在全部可见会话上的未读总数，用于 Redis 未读缓存回源。
     */
    @Select("""
            SELECT COALESCE(SUM(m.unread_count), 0)
            FROM im_conversation_member m
                     JOIN im_conversation c ON c.id = m.conversation_id AND c.deleted = 0
            WHERE m.user_id = #{userId}
              AND m.is_deleted = 0
            """)
    long sumUnread(@Param("userId") Long userId);

    /**
     * 查询我在某会话上的成员行。
     *
     * @return 不是成员时返回 {@code null}
     */
    default ConversationMember selectByConvAndUser(Long conversationId, Long userId) {
        return selectOne(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    /**
     * 查询会话的全部成员 ID。
     */
    default List<Long> selectMemberIds(Long conversationId) {
        return selectList(Wrappers.<ConversationMember>lambdaQuery()
                        .select(ConversationMember::getUserId)
                        .eq(ConversationMember::getConversationId, conversationId))
                .stream()
                .map(ConversationMember::getUserId)
                .toList();
    }

    /**
     * 批量查询若干会话中「除我以外」的成员，用于解析单聊对方。
     *
     * <p>只取两列，避免把整行拉回来；单聊会话每人只会命中一行。
     */
    default List<ConversationMember> selectPeers(Collection<Long> conversationIds, Long excludeUserId) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<ConversationMember>lambdaQuery()
                .select(ConversationMember::getConversationId, ConversationMember::getUserId)
                .in(ConversationMember::getConversationId, conversationIds)
                .ne(ConversationMember::getUserId, excludeUserId));
    }

    /**
     * 原子递增未读数。
     *
     * <p>必须用 {@code setSql} 走数据库端的 {@code unread_count = unread_count + n}：
     * 若改成「读出 - 加一 - 写回」，同一用户多端同时收消息时会互相覆盖丢计数。
     * {@code delta} 为 int 类型，直接拼接不存在注入风险。
     */
    default int increaseUnread(Long conversationId, Collection<Long> userIds, int delta) {
        if (userIds == null || userIds.isEmpty() || delta == 0) {
            return 0;
        }
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .setSql("unread_count = unread_count + " + delta)
                .eq(ConversationMember::getConversationId, conversationId)
                .in(ConversationMember::getUserId, userIds));
    }

    /**
     * 清零未读数与 @ 提醒标记，并同步推进接收位点与已读位点。
     *
     * <p>本方法只在用户真正打开会话（markRead）时调用，所以两个位点一起前进是语义正确的；
     * 离线拉取走的是 {@link #advanceAck}，只推接收位点，不能把未看过的消息算成已读。
     * 位点用 {@code GREATEST} 只允许前进：旧客户端重放已读上报时，
     * 不能把位点往回拉，否则离线消息会被重复推送。
     */
    default int resetUnread(Long conversationId, Long userId, Long lastAckSeq) {
        var wrapper = Wrappers.<ConversationMember>lambdaUpdate()
                .set(ConversationMember::getUnreadCount, 0)
                .set(ConversationMember::getAtFlag, 0)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId);
        if (lastAckSeq != null) {
            wrapper.setSql("last_ack_seq = GREATEST(last_ack_seq, " + lastAckSeq + ")")
                    .setSql("last_read_seq = GREATEST(last_read_seq, " + lastAckSeq + ")");
        }
        return update(null, wrapper);
    }

    /**
     * 查询我在某会话上的已读位点，供 markRead 计算本次新覆盖的 seq 区间。
     */
    default Long selectReadSeq(Long conversationId, Long userId) {
        ConversationMember member = selectOne(Wrappers.<ConversationMember>lambdaQuery()
                .select(ConversationMember::getLastReadSeq)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .last("LIMIT 1"));
        return member == null || member.getLastReadSeq() == null ? 0L : member.getLastReadSeq();
    }

    /**
     * 查询会话全部成员（可排除一人）的双位点，群聊送达/已读人数推算的唯一数据源。
     *
     * <p>只取三列不碰消息表：无论群多大、历史消息多少，每次只扫这几行，
     * 正是「不存每条消息的已读记录」防表爆炸策略的读侧。
     */
    default List<ConversationMember> selectMemberPositions(Long conversationId, Long excludeUserId) {
        var wrapper = Wrappers.<ConversationMember>lambdaQuery()
                .select(ConversationMember::getUserId, ConversationMember::getLastAckSeq, ConversationMember::getLastReadSeq)
                .eq(ConversationMember::getConversationId, conversationId);
        if (excludeUserId != null) {
            wrapper.ne(ConversationMember::getUserId, excludeUserId);
        }
        return selectList(wrapper);
    }

    /**
     * 查询用户在全部会话上的已确认位点，仅取两列。
     *
     * <p>刻意不过滤 {@code is_deleted}：用户本端隐藏了会话，隐藏期间对方发的消息仍然要在
     * 下次上线时补齐，否则隐藏会话会变成永久丢消息的黑洞。
     */
    default List<ConversationMember> selectAckPositions(Long userId) {
        return selectList(Wrappers.<ConversationMember>lambdaQuery()
                .select(ConversationMember::getConversationId, ConversationMember::getLastAckSeq)
                .eq(ConversationMember::getUserId, userId));
    }

    /**
     * 仅推进已确认位点，不动未读数。
     *
     * <p>与 {@link #resetUnread} 分开是为了区分「客户端已拉到」与「用户已看过」两件事：
     * 离线消息拉取完成后只应该推进位点，未读红点要等用户真正点开会话才消失。
     */
    default int advanceAck(Long conversationId, Long userId, Long lastAckSeq) {
        if (lastAckSeq == null) {
            return 0;
        }
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .setSql("last_ack_seq = GREATEST(last_ack_seq, " + lastAckSeq + ")")
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
    }

    /**
     * 标记被 @ 提醒，供前端展示红点；同时把本端隐藏的会话重新露出。
     */
    default int markAtFlag(Long conversationId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .set(ConversationMember::getAtFlag, 1)
                .eq(ConversationMember::getConversationId, conversationId)
                .in(ConversationMember::getUserId, userIds));
    }

    /**
     * 新消息到达时把本端隐藏的会话重新露出：用户主动隐藏后对方再发消息，会话应当回到列表。
     */
    default int reveal(Collection<Long> conversationIds, Collection<Long> userIds) {
        if (conversationIds == null || conversationIds.isEmpty() || userIds == null || userIds.isEmpty()) {
            return 0;
        }
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .set(ConversationMember::getHidden, 0)
                .in(ConversationMember::getConversationId, conversationIds)
                .in(ConversationMember::getUserId, userIds)
                .eq(ConversationMember::getHidden, 1));
    }

    /**
     * 置顶 / 取消置顶。取消时清空置顶时间，避免下次置顶沿用过期的排序权重。
     */
    default int updateTop(Long conversationId, Long userId, boolean top) {
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .set(ConversationMember::getTop, top ? 1 : 0)
                .set(ConversationMember::getTopTime, top ? LocalDateTime.now() : null)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
    }

    /**
     * 消息免打扰开关。免打扰只影响提醒与未读红点，未读数本身照常累加。
     */
    default int updateMuted(Long conversationId, Long userId, boolean muted) {
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .set(ConversationMember::getMuted, muted ? 1 : 0)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
    }

    /**
     * 本端隐藏 / 恢复会话，不影响会话主体与消息。
     */
    default int updateHidden(Long conversationId, Long userId, boolean hidden) {
        return update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .set(ConversationMember::getHidden, hidden ? 1 : 0)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
    }
}
