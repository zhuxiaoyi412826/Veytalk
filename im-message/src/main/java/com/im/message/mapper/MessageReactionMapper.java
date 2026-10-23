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
 *
 * <p>★ 一个用户对一条消息只保留**最后一个**表情（后贴的顶掉先贴的），所以下面所有查询
 * 都按 {@code create_time} 正序：聚合时「同一人取最后一行」依赖这个顺序，不能省。
 */
@Mapper
public interface MessageReactionMapper extends BaseMapper<MessageReaction> {

    /**
     * 查出这批消息的全部回应行，供调用方按 messageId + emoji 聚合。
     *
     * <p>按回应时间正序返回：同一人多条旧数据（历史遗留的「一人多表情」）要靠这个顺序取最后一条。
     */
    default List<MessageReaction> selectByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageReaction>lambdaQuery()
                .in(MessageReaction::getMessageId, messageIds)
                .orderByAsc(MessageReaction::getCreateTime)
                .orderByAsc(MessageReaction::getId));
    }

    /**
     * 某条消息的全部回应行，供单条返回（如 react 接口回带最新视图）时聚合。
     */
    default List<MessageReaction> selectByMessageId(Long messageId) {
        return selectList(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getMessageId, messageId)
                .orderByAsc(MessageReaction::getCreateTime)
                .orderByAsc(MessageReaction::getId));
    }

    /**
     * 某用户在这条消息上当前的回应（正序，正常只有一行）。
     *
     * <p>贴新表情前要用它算出「被顶掉的那个」，好在通知里一并告知各端把它减掉。
     */
    default List<MessageReaction> selectByMessageAndUser(Long messageId, Long userId) {
        return selectList(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getMessageId, messageId)
                .eq(MessageReaction::getUserId, userId)
                .orderByAsc(MessageReaction::getCreateTime)
                .orderByAsc(MessageReaction::getId));
    }

    /**
     * 某用户在这条消息上的表情是否为这个（只比一行，供重复点同一个时幂等早退）。
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
     * 删掉某用户在这条消息上除 {@code keepEmoji} 以外的全部回应。
     *
     * <p>「一人一条、只留最后一个」就落在这里：唯一键 {@code uk_msg_user_emoji} 允许一人多表情，
     * 语义上的「换表情」得由服务层显式清掉旧的，而不是改唯一键（改键要动 DDL，存量数据还得洗）。
     */
    default int deleteOthersByUser(Long messageId, Long userId, String keepEmoji) {
        return delete(Wrappers.<MessageReaction>lambdaQuery()
                .eq(MessageReaction::getMessageId, messageId)
                .eq(MessageReaction::getUserId, userId)
                .ne(MessageReaction::getEmoji, keepEmoji));
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
