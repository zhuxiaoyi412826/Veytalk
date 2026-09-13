package com.im.conversation.service;

import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.MessageEvent;
import com.im.conversation.dto.vo.ConversationVO;
import com.im.conversation.entity.Conversation;
import com.im.conversation.entity.ConversationMember;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 会话服务。
 *
 * <p>除面向前端的业务方法外，还包含一批供 {@code ConversationSpiImpl} 委派的跨模块原语
 * （创建、未读递增、成员增删、消息回调）。把这些原语集中在 Service 而不是分散到 SPI 实现里，
 * 是为了让事务边界与并发防护只有一处实现，避免两条路径行为不一致。
 */
public interface ConversationService {

    /* ==================== 面向前端 ==================== */

    /**
     * 当前用户的会话列表，已过滤本端隐藏的会话，并按置顶与最新消息时间排序。
     */
    List<ConversationVO> list(Long userId);

    /**
     * 会话详情，非成员抛 {@code CONVERSATION_NO_PERMISSION}。
     */
    ConversationVO detail(Long userId, Long conversationId);

    /**
     * 创建或获取与某用户的单聊会话，会先校验好友关系与拉黑状态。
     *
     * @return 会话 ID
     */
    Long createSingle(Long userId, Long targetUserId);

    /**
     * 标记会话已读：清零未读、清除 @ 提醒、推进已读位点，并通知 im-message 发送已读回执。
     */
    void markRead(Long userId, Long conversationId, Long lastAckSeq);

    /**
     * 置顶 / 取消置顶。
     */
    void setTop(Long userId, Long conversationId, boolean enabled);

    /**
     * 消息免打扰开关。
     */
    void setMute(Long userId, Long conversationId, boolean enabled);

    /**
     * 本端隐藏会话，不删除消息，对方再发消息时会重新露出。
     */
    void hide(Long userId, Long conversationId, boolean hidden);

    /**
     * 全部会话未读总和，命中 Redis 缓存时不回源数据库。
     */
    long unreadTotal(Long userId);

    /* ==================== 跨模块原语 ==================== */

    /**
     * 获取或创建单聊会话，幂等，不做好友校验（供同意好友申请等内部场景使用）。
     */
    Long getOrCreateSingle(Long userA, Long userB);

    /**
     * 获取或创建群聊会话，幂等。
     */
    Long getOrCreateGroup(Long groupId, Collection<Long> memberIds);

    /**
     * 查询已存在的单聊会话 ID。
     *
     * @return 不存在时返回 {@code null}
     */
    Long findSingle(Long userA, Long userB);

    /**
     * 查询已存在的群聊会话 ID，只读不创建。
     *
     * @return 不存在时返回 {@code null}
     */
    Long findGroup(Long groupId);

    /**
     * 会话摘要，不存在时返回 {@code null}。
     */
    ConversationBriefDTO brief(Long conversationId);

    /**
     * 取出会话主体，不存在抛 {@code CONVERSATION_NOT_FOUND}。
     */
    Conversation requireConversation(Long conversationId);

    /**
     * 取出我在会话上的成员行，不是成员抛 {@code CONVERSATION_NO_PERMISSION}。
     */
    ConversationMember requireMember(Long userId, Long conversationId);

    /**
     * 会话全部成员 ID。
     */
    List<Long> memberIds(Long conversationId);

    /**
     * 是否为会话成员。
     */
    boolean isMember(Long conversationId, Long userId);

    /**
     * 会话目标：单聊返回对方用户 ID，群聊返回群 ID。
     */
    Long targetIdOf(Long conversationId, Long viewerId);

    /**
     * 新消息回调：刷新会话摘要、给接收者原子递增未读、清除本端隐藏、记录 @ 提醒、失效未读缓存。
     */
    void onNewMessage(MessageEvent event);

    /**
     * 消息撤回回调：撤回的是最后一条消息时刷新会话摘要。
     */
    void onMessageRecalled(Long conversationId, Long messageId, String summary);

    /**
     * 清零未读（不推送已读回执），供 im-message 内部调用。
     */
    void clearUnread(Long userId, Long conversationId, Long lastAckSeq);

    /**
     * 仅推进已确认位点，不清未读、不推送，供离线消息拉取完成后回调。
     */
    void advanceAck(Long userId, Long conversationId, Long lastAckSeq);

    /**
     * 用户在全部会话上的已确认位点，含本端已隐藏的会话。
     *
     * @return key 为 conversationId，value 为 last_ack_seq
     */
    Map<Long, Long> ackPositions(Long userId);

    /**
     * 批量新增会话成员，已存在的成员自动跳过。
     */
    void addMembers(Long conversationId, Collection<Long> userIds);

    /**
     * 移除会话成员。
     */
    void removeMember(Long conversationId, Long userId);

    /**
     * 用户维度的会话摘要列表，供其他模块批量获取会话信息。
     */
    List<ConversationBriefDTO> listBriefByUser(Long userId);
}
