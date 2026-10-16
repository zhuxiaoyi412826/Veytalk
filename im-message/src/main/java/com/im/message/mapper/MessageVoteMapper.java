package com.im.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.message.entity.MessageVote;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 消息投票选项 Mapper。
 *
 * <p>投票只在渲染消息列表时用于聚合展示（每个选项的票数、总数、本人是否已选），因此批量查询
 * 一次把整页投票的选项行取回内存分组，避免逐条回表。重新投票用 {@link #deleteByUser} 先清后写。
 */
@Mapper
public interface MessageVoteMapper extends BaseMapper<MessageVote> {

    /** 查出这批消息的全部投票选项行，供调用方按 messageId + optionIndex 聚合。 */
    default List<MessageVote> selectByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageVote>lambdaQuery()
                .in(MessageVote::getMessageId, messageIds));
    }

    /** 某用户对某条投票清空既有选择（重新投票前的重置），删 0 行不报错。 */
    default int deleteByUser(Long messageId, Long userId) {
        return delete(Wrappers.<MessageVote>lambdaQuery()
                .eq(MessageVote::getMessageId, messageId)
                .eq(MessageVote::getUserId, userId));
    }

    /** 物理清理一批消息的全部投票选项行，供「账号注销 / 数据删除」等级联删除复用。 */
    default int deleteByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return delete(Wrappers.<MessageVote>lambdaQuery()
                .in(MessageVote::getMessageId, messageIds));
    }
}
