package com.im.conversation.spi;

import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.MessageEvent;
import com.im.common.spi.ConversationSpi;
import com.im.conversation.entity.Conversation;
import com.im.conversation.mapper.ConversationMapper;
import com.im.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@link ConversationSpi} 在本模块的实现，供 im-friend / im-group / im-message / im-websocket 跨模块调用。
 *
 * <p>这一层只做薄委派，不含任何业务判断：事务边界、唯一键并发防护、未读原子递增全部集中在
 * {@link com.im.conversation.service.impl.ConversationServiceImpl}，避免「SPI 一条路径、Controller 另一条路径」两份实现行为漂移。
 *
 * <p>唯一直接用到 Mapper 的是 {@link #getType(Long)}——它在会话不存在时返回 {@code null}，
 * 而 Service 的 {@code requireConversation} 是抛异常的语义，调用方（消息模块校验会话有效性）
 * 需要的是「查不到就当作非法会话」而不是异常穿透。
 */
@Component
@RequiredArgsConstructor
public class ConversationSpiImpl implements ConversationSpi {

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;

    @Override
    public Long getOrCreateSingle(Long userA, Long userB) {
        return conversationService.getOrCreateSingle(userA, userB);
    }

    @Override
    public Long getOrCreateGroup(Long groupId, Collection<Long> memberIds) {
        return conversationService.getOrCreateGroup(groupId, memberIds);
    }

    @Override
    public Long findSingle(Long userA, Long userB) {
        return conversationService.findSingle(userA, userB);
    }

    @Override
    public Long findGroup(Long groupId) {
        return conversationService.findGroup(groupId);
    }

    @Override
    public ConversationBriefDTO getById(Long conversationId) {
        return conversationService.brief(conversationId);
    }

    @Override
    public Integer getType(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        return conversation == null ? null : conversation.getType();
    }

    @Override
    public Long getTargetId(Long conversationId, Long viewerId) {
        return conversationService.targetIdOf(conversationId, viewerId);
    }

    @Override
    public boolean isMember(Long conversationId, Long userId) {
        return conversationService.isMember(conversationId, userId);
    }

    @Override
    public List<Long> getMemberIds(Long conversationId) {
        return conversationService.memberIds(conversationId);
    }

    @Override
    public void onNewMessage(MessageEvent event) {
        conversationService.onNewMessage(event);
    }

    @Override
    public void onMessageRecalled(Long conversationId, Long messageId, String summary) {
        conversationService.onMessageRecalled(conversationId, messageId, summary);
    }

    @Override
    public void clearUnread(Long userId, Long conversationId, Long lastAckSeq) {
        conversationService.clearUnread(userId, conversationId, lastAckSeq);
    }

    @Override
    public void advanceAck(Long userId, Long conversationId, Long lastAckSeq) {
        conversationService.advanceAck(userId, conversationId, lastAckSeq);
    }

    @Override
    public Map<Long, Long> getAckPositions(Long userId) {
        return conversationService.ackPositions(userId);
    }

    @Override
    public void addMembers(Long conversationId, Collection<Long> userIds) {
        conversationService.addMembers(conversationId, userIds);
    }

    @Override
    public void removeMember(Long conversationId, Long userId) {
        conversationService.removeMember(conversationId, userId);
    }

    @Override
    public List<ConversationBriefDTO> listByUser(Long userId) {
        return conversationService.listBriefByUser(userId);
    }
}
