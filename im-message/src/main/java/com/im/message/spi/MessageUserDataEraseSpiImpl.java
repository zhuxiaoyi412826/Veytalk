package com.im.message.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.spi.UserDataEraseSpi;
import com.im.message.entity.MessageDelete;
import com.im.message.entity.MessageFavorite;
import com.im.message.entity.MessageRead;
import com.im.message.entity.MessageReaction;
import com.im.message.entity.MessageVote;
import com.im.message.mapper.MessageDeleteMapper;
import com.im.message.mapper.MessageFavoriteMapper;
import com.im.message.mapper.MessageReadMapper;
import com.im.message.mapper.MessageReactionMapper;
import com.im.message.mapper.MessageVoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账号注销时清除 im-message 持有的「用户行为数据」：本人贴的表情回应、投的票、已读/送达回执、
 * 单端删除记录、收藏标记，全部物理删除——这些都是直接绑定到本人身份、且可独立清除而不影响他人的数据。
 *
 * <p>本人发出的 {@code im_message} 行不物理删除：消息是会话双方（或全群）的共享历史，
 * 强删会破坏他人视图与 seq 连续性。此处采用 GDPR 认可的「假名化」——{@code im_user} 主记录已在
 * 注销时被匿名化并逻辑删除，{@code from_user_id} 不再能关联到可识别的个人，等价于去标识化处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageUserDataEraseSpiImpl implements UserDataEraseSpi {

    private final MessageReactionMapper messageReactionMapper;
    private final MessageVoteMapper messageVoteMapper;
    private final MessageReadMapper messageReadMapper;
    private final MessageDeleteMapper messageDeleteMapper;
    private final MessageFavoriteMapper messageFavoriteMapper;

    @Override
    public void eraseUserData(Long userId) {
        if (userId == null) {
            return;
        }
        int reactions = messageReactionMapper.delete(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getUserId, userId));
        int votes = messageVoteMapper.delete(Wrappers.<MessageVote>lambdaQuery()
                .eq(MessageVote::getUserId, userId));
        int reads = messageReadMapper.delete(Wrappers.<MessageRead>lambdaQuery()
                .eq(MessageRead::getUserId, userId));
        int deletes = messageDeleteMapper.delete(Wrappers.<MessageDelete>lambdaQuery()
                .eq(MessageDelete::getUserId, userId));
        int favorites = messageFavoriteMapper.delete(Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId));
        log.info("[账号注销] im-message 清除 userId={}：回应 {} 行、投票 {} 行、已读 {} 行、单端删除 {} 行、收藏 {} 行",
                userId, reactions, votes, reads, deletes, favorites);
    }
}
