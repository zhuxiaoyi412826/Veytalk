package com.im.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.message.entity.MessageReaction;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 消息表情回应 Mapper。
 *
 * <p>回应只在渲染消息列表时用于聚合展示（每条消息下「👍 3 / ❤️ 1」这类），因此批量查询一次把
 * 整页消息的回应行取回内存分组，避免逐条回表。增删走 {@code BaseMapper} 的 insert/delete。
 */
@Mapper
public interface MessageReactionMapper extends BaseMapper<MessageReaction> {

    /**
     * 查出这批消息的全部回应行，供调用方按 messageId + emoji 聚合。
     */
    default List<MessageReaction> selectByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageReaction>lambdaQuery()
                .in(MessageReaction::getMessageId, messageIds));
    }

    /**
     * 某条消息的全部回应行，供单条返回（如 react 接口回带最新视图）时聚合。
     */
    default List<MessageReaction> selectByMessageId(Long messageId) {
        return selectList(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getMessageId, messageId));
    }

    /**
     * 某用户对某条消息的某个表情是否已存在，用于 toggle 判定加还是删。
     */
    default boolean exists(Long messageId, Long userId, String emoji) {
        return selectCount(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getMessageId, messageId)
                .eq(MessageReaction::getUserId, userId)
                .eq(MessageReaction::getEmoji, emoji)) > 0;
    }

    /**
     * 删除某用户对某条消息某个表情的回应（取消）。删 0 行不报错，幂等。
     */
    default int deleteOne(Long messageId, Long userId, String emoji) {
        return delete(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getMessageId, messageId)
                .eq(MessageReaction::getUserId, userId)
                .eq(MessageReaction::getEmoji, emoji));
    }

    /**
     * 物理清理一批消息的全部回应行，供后续「账号注销 / 数据删除」等级联删除复用。
     */
    default int deleteByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return delete(Wrappers.<MessageReaction>lambdaQuery()
                .in(MessageReaction::getMessageId, messageIds));
    }
}
