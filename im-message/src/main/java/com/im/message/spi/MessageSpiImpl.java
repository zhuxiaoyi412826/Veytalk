package com.im.message.spi;

import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageSendCmd;
import com.im.common.spi.MessageSpi;
import com.im.message.dto.req.ForwardMessageRequest;
import com.im.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;


/**
 * {@link MessageSpi} 在本模块的实现，供 im-friend / im-group / im-websocket 跨模块调用。
 *
 * <p>这一层只做薄委派，一行判断都不加：幂等、权限校验、事务边界、推送时机全部集中在
 * {@link com.im.message.service.impl.MessageServiceImpl}，避免「SPI 一条路径、REST 另一条路径」
 * 两份实现随时间漂移——消息发送是全系统最容易出一致性问题的地方，只能有一份逻辑。
 *
 * <p>唯一的语义差异是 {@link #listOffline}：它返回跨模块的 {@link MessageDTO}，
 * 因为调用方是 WebSocket 模块，需要的是可直接序列化成报文的传输对象，而不是带渲染字段的视图。
 */
@Component
@RequiredArgsConstructor
public class MessageSpiImpl implements MessageSpi {

    private final MessageService messageService;

    @Override
    public MessageDTO send(MessageSendCmd cmd) {
        return messageService.send(cmd);
    }

    @Override
    public MessageDTO sendSystemNotice(Long conversationId, String content) {
        return messageService.sendSystemNotice(conversationId, content);
    }

    @Override
    public MessageDTO getById(Long messageId) {
        return messageService.findDto(messageId);
    }

    @Override
    public boolean isFileVisibleTo(Long fileId, Long uploaderId, Long viewerId) {
        return messageService.fileVisibleTo(fileId, uploaderId, viewerId);
    }

    @Override
    public void recall(Long messageId, Long operatorId) {
        messageService.recall(messageId, operatorId);
    }

    @Override
    public void markDelivered(Long userId, Collection<Long> messageIds) {
        messageService.markDelivered(userId, messageIds);
    }

    @Override
    public void markRead(Long userId, Long conversationId, Long maxSeq) {
        messageService.markRead(userId, conversationId, maxSeq);
    }

    @Override
    public List<MessageDTO> listOffline(Long userId) {
        return messageService.offlineDto(userId);
    }

    @Override
    public void clearOffline(Long userId) {
        messageService.clearOffline(userId);
    }

    @Override
    public MessageDTO forward(Long userId, Long messageId, Long conversationId,
                              Long toUserId, Long toGroupId, String clientMsgId) {
        ForwardMessageRequest request = new ForwardMessageRequest();
        request.setMessageId(messageId);
        request.setConversationId(conversationId);
        request.setToUserId(toUserId);
        request.setToGroupId(toGroupId);
        request.setClientMsgId(clientMsgId);
        return messageService.forwardDto(userId, request);
    }
}
