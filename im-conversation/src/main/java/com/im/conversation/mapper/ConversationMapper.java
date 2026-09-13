package com.im.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.conversation.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 会话 Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 按业务唯一键查询会话，{@code getOrCreate} 幂等的基础。
     *
     * @return 不存在时返回 {@code null}
     */
    default Conversation selectByBizKey(String bizKey) {
        return selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getBizKey, bizKey)
                .last("LIMIT 1"));
    }

    /**
     * 更新会话的最后一条消息摘要。
     *
     * <p>条件里额外要求 {@code last_msg_time IS NULL OR last_msg_time <= ?}：
     * 并发或重试场景下旧消息可能晚于新消息到达，若不设防护，会话列表会显示出
     * 比实际更旧的一条摘要。
     *
     * @return 影响行数，0 表示被更新的消息已被更晚的消息覆盖
     */
    default int updateLastMessage(Long conversationId, Long messageId, String content,
                                  Integer msgType, LocalDateTime msgTime) {
        return update(null, Wrappers.<Conversation>lambdaUpdate()
                .set(Conversation::getLastMsgId, messageId)
                .set(Conversation::getLastMsgContent, content)
                .set(Conversation::getLastMsgType, msgType)
                .set(Conversation::getLastMsgTime, msgTime)
                .eq(Conversation::getId, conversationId)
                .and(w -> w.isNull(Conversation::getLastMsgTime).or().le(Conversation::getLastMsgTime, msgTime)));
    }

    /**
     * 按 ID 批量查询会话主体，供列表组装使用。
     */
    default List<Conversation> selectByIdIn(Collection<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Conversation>lambdaQuery()
                .in(Conversation::getId, conversationIds));
    }
}
