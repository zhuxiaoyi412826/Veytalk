package com.im.common.spi;

import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.MessageEvent;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 会话契约，由 im-conversation 模块实现。
 */
public interface ConversationSpi {

    /**
     * 获取或创建单聊会话，幂等。会话唯一键为 {@code s:{minUserId}:{maxUserId}}。
     *
     * @return 会话 ID
     */
    Long getOrCreateSingle(Long userA, Long userB);

    /**
     * 获取或创建群聊会话，幂等。会话唯一键为 {@code g:{groupId}}。
     *
     * @param memberIds 初始成员（含群主）
     * @return 会话 ID
     */
    Long getOrCreateGroup(Long groupId, Collection<Long> memberIds);

    /**
     * 单聊会话是否已存在。
     */
    Long findSingle(Long userA, Long userB);

    /**
     * 查询已存在的群聊会话 ID，只读不创建。
     *
     * <p>与 {@link #getOrCreateGroup} 的区别：群详情、我的群列表这类纯读场景需要拿到会话 ID
     * 供前端跳转，但绝不能顺手建出一个成员不全的会话；群会话只应在建群时创建一次。
     *
     * @return 不存在时返回 {@code null}
     */
    Long findGroup(Long groupId);

    /**
     * 按 ID 查询会话摘要（不含成员视角字段）。
     *
     * @return 不存在时返回 {@code null}
     */
    ConversationBriefDTO getById(Long conversationId);

    /**
     * 会话类型：1 单聊 2 群聊。
     */
    Integer getType(Long conversationId);

    /**
     * 会话目标：单聊返回对方用户 ID（相对 viewer），群聊返回群 ID。
     */
    Long getTargetId(Long conversationId, Long viewerId);

    /**
     * 是否为会话成员。
     */
    boolean isMember(Long conversationId, Long userId);

    /**
     * 获取会话全部成员 ID。
     */
    List<Long> getMemberIds(Long conversationId);

    /**
     * 新消息落库后回调：更新会话摘要、递增未读数、刷新未读缓存、记录 @ 提醒。
     */
    void onNewMessage(MessageEvent event);

    /**
     * 消息撤回后回调：若撤回的是最后一条消息则刷新会话摘要。
     */
    void onMessageRecalled(Long conversationId, Long messageId, String summary);

    /**
     * 清零指定会话未读数并记录已读位点。
     */
    void clearUnread(Long userId, Long conversationId, Long lastAckSeq);

    /**
     * 仅推进已确认接收位点，不清未读数。离线消息拉取完成后调用，
     * 拉取不等于已读，未读计数仍由用户真正查看会话时才清零。
     */
    void advanceAck(Long userId, Long conversationId, Long lastAckSeq);

    /**
     * 用户在全部会话上的已确认位点，含本端已隐藏的会话。
     *
     * @return key 为 conversationId，value 为 last_ack_seq
     */
    Map<Long, Long> getAckPositions(Long userId);

    /**
     * 批量新增会话成员（入群）。
     */
    void addMembers(Long conversationId, Collection<Long> userIds);

    /**
     * 移除会话成员（退群 / 被移出）。
     */
    void removeMember(Long conversationId, Long userId);

    /**
     * 用户维度的会话列表，按置顶与最新消息时间排序。
     */
    List<ConversationBriefDTO> listByUser(Long userId);
}
