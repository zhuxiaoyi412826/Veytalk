package com.im.conversation.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.spi.UserDataEraseSpi;
import com.im.conversation.entity.ConversationMember;
import com.im.conversation.mapper.ConversationMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账号注销时清除 im-conversation 持有的用户数据：删除该用户的全部会话成员行
 * （未读数、置顶、免打扰、本端隐藏等私有视角）。
 *
 * <p>会话主体 {@code im_conversation} 不删：它承载的是双方共享的历史，另一方的成员行仍在，
 * 会话对用户本人视角随成员行删除而消失，符合 GDPR「删除与本人相关数据」的边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationUserDataEraseSpiImpl implements UserDataEraseSpi {

    private final ConversationMemberMapper conversationMemberMapper;

    @Override
    public void eraseUserData(Long userId) {
        if (userId == null) {
            return;
        }
        int rows = conversationMemberMapper.delete(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getUserId, userId));
        log.info("[账号注销] im-conversation 清除 userId={}：会话成员行 {} 行", userId, rows);
    }
}
