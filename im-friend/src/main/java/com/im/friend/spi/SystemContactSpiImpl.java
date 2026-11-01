package com.im.friend.spi;

import com.im.common.constant.ImConstants;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.SystemContactSpi;
import com.im.friend.service.FriendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * {@link SystemContactSpi} 的实现：给新用户装配客服与文件传输助手两个保留联系人。
 *
 * <p>由 im-user 的注册钩子在事务提交后调用，因此这里没有活动事务——{@code bindRelation}、
 * {@code getOrCreateSingle}、{@code sendSystemNotice} 各自带独立事务，逐条 best-effort：
 * 任何一条失败都只记日志，不回拖注册。缺会话或缺消息模块（单跑 im-friend）时对应步骤自动跳过，
 * 好友关系仍能建立。
 *
 * <p>幂等由底层保证：{@code bindRelation} 命中唯一键转状态恢复、{@code getOrCreateSingle} 按
 * {@code biz_key} 幂等，重复调用（重跑、并发）不会长出重复关系或重复会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemContactSpiImpl implements SystemContactSpi {

    /** 客服会话首条欢迎语 */
    private static final String NOTICE_SERVICE = "我是您的专属客服，使用上有任何问题都可以随时在这里找我～";
    /** 文件传输助手会话首条说明 */
    private static final String NOTICE_FILE_HELPER = "把文件或消息发到这里，即可在同一账号的电脑端接收，相当于微信的文件传输助手。";

    private final FriendService friendService;
    private final ObjectProvider<ConversationSpi> conversationSpiProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;

    @Override
    public void provisionContacts(Long userId) {
        if (userId == null || ImConstants.SYSTEM_CONTACT_IDS.contains(userId)) {
            return;
        }
        provisionOne(userId, ImConstants.SERVICE_USER_ID, NOTICE_SERVICE);
        provisionOne(userId, ImConstants.FILE_HELPER_USER_ID, NOTICE_FILE_HELPER);
    }

    /**
     * 建立「用户 ↔ 某个保留账号」的关系 + 会话 + 首条系统通知，每步单独吞异常。
     */
    private void provisionOne(Long userId, Long contactId, String notice) {
        try {
            friendService.bindRelation(userId, contactId);
        } catch (Exception e) {
            log.warn("[系统联系人] 建立好友关系失败: userId={}, contactId={}, {}", userId, contactId, e.getMessage());
            return;
        }
        Long conversationId = null;
        ConversationSpi conversationSpi = conversationSpiProvider.getIfAvailable();
        if (conversationSpi != null) {
            try {
                conversationId = conversationSpi.getOrCreateSingle(userId, contactId);
            } catch (Exception e) {
                log.warn("[系统联系人] 创建单聊会话失败: userId={}, contactId={}, {}", userId, contactId, e.getMessage());
            }
        }
        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        if (conversationId != null && messageSpi != null) {
            try {
                messageSpi.sendSystemNotice(conversationId, notice);
            } catch (Exception e) {
                log.warn("[系统联系人] 发送系统通知失败: conversationId={}, {}", conversationId, e.getMessage());
            }
        }
        log.info("[系统联系人] 装配完成: userId={}, contactId={}, conversationId={}", userId, contactId, conversationId);
    }
}
