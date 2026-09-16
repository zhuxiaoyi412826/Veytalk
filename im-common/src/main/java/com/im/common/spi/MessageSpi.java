package com.im.common.spi;

import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageSendCmd;

import java.util.Collection;
import java.util.List;


/**
 * 消息契约，由 im-message 模块实现。
 */
public interface MessageSpi {

    /**
     * 发送消息：校验 -> 幂等 -> 落库 -> 更新会话 -> WebSocket 推送。
     *
     * @return 落库后的消息，重复提交时返回首次结果
     */
    MessageDTO send(MessageSendCmd cmd);

    /**
     * 发送系统通知消息，发送者为系统账号，跳过好友与禁言校验。
     *
     * @param conversationId 目标会话
     * @param content        通知文案
     */
    MessageDTO sendSystemNotice(Long conversationId, String content);

    /**
     * 按 ID 查询消息。
     *
     * @return 不存在时返回 {@code null}
     */
    MessageDTO getById(Long messageId);

    /**
     * 用户能否看到引用了指定文件的消息，文件下载鉴权的唯一依据。
     *
     * <p>这个问题只能由消息模块回答：只有它知道哪些消息引用了这个文件、
     * 这些消息又落在哪些会话里。文件模块自己拿「上传者」做判定是不够的，
     * 群聊里成员彼此并非好友，按好友关系卡会把正常的群图片全部挡掉。
     *
     * @param uploaderId 文件上传者，引用该文件的消息必然由他发出（发送时已做归属校验），
     *                   传入后可把查询收敛到「上传者的消息」而不是「查看者的全部会话」
     * @param viewerId   发起下载的用户
     * @return 两人共同所在的会话中存在引用该文件的未撤回消息时返回 {@code true}；
     *         查看者就是上传者时直接返回 {@code true}（他可能刚上传完还没发出去）
     */
    boolean isFileVisibleTo(Long fileId, Long uploaderId, Long viewerId);

    /**
     * 撤回消息。
     *
     * @param messageId  消息 ID
     * @param operatorId 操作人 ID
     */
    void recall(Long messageId, Long operatorId);

    /**
     * 批量标记消息已送达。
     *
     * @param userId     接收者 ID
     * @param messageIds 消息 ID 列表
     */
    void markDelivered(Long userId, Collection<Long> messageIds);

    /**
     * 标记会话内消息已读，并推送已读回执给发送方。
     *
     * @param userId         阅读者 ID
     * @param conversationId 会话 ID
     * @param maxSeq         已读到的最大序列号，为空表示整个会话
     */
    void markRead(Long userId, Long conversationId, Long maxSeq);

    /**
     * 查询用户尚未拉取的离线消息，按会话与序列号升序返回。
     */
    List<MessageDTO> listOffline(Long userId);

    /**
     * 清除离线消息标记。
     */
    void clearOffline(Long userId);

    /**
     * 转发消息：把一条已存在的消息复制到目标会话。
     *
     * <p>转发者必须是原消息所在会话的成员；附件复用原文件不重新上传，
     * 目标会话定位优先级 {@code conversationId} &gt; {@code toUserId} &gt; {@code toGroupId}。
     *
     * @param userId         转发操作者
     * @param messageId      被转发的原消息 ID
     * @param conversationId 目标会话 ID，与 toUserId/toGroupId 三选一
     * @param toUserId       单聊目标用户 ID
     * @param toGroupId      群聊目标群 ID
     * @param clientMsgId    幂等键，为空时由服务端生成
     * @return 落库后的新消息
     */
    MessageDTO forward(Long userId, Long messageId, Long conversationId,
                       Long toUserId, Long toGroupId, String clientMsgId);
}
