package com.im.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.message.entity.MessageEssence;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 群精华消息标记 Mapper。
 *
 * <p>两个读取方向：随消息列表批量判定「哪些条是精华」（{@link #selectByMessageIds}，整页一次取行内存分组），
 * 以及「按会话列出群内全部精华」（{@link #pageByConversation}，供群设置的精华沉淀面板分页浏览）。
 * 增删走 {@code BaseMapper} 的 insert / delete。
 */
@Mapper
public interface MessageEssenceMapper extends BaseMapper<MessageEssence> {

    /** 查出这批消息中哪些被标为精华（返回对应行），供调用方按 messageId 集合标记 VO.essence。 */
    default List<MessageEssence> selectByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageEssence>lambdaQuery()
                .in(MessageEssence::getMessageId, messageIds));
    }

    /** 某条消息是否已是精华。 */
    default boolean existsByMessage(Long messageId) {
        return selectCount(Wrappers.<MessageEssence>lambdaQuery()
                .eq(MessageEssence::getMessageId, messageId)) > 0;
    }

    /** 取消精华：删除该消息的精华行。删 0 行不报错，幂等。 */
    default int deleteByMessageId(Long messageId) {
        return delete(Wrappers.<MessageEssence>lambdaQuery()
                .eq(MessageEssence::getMessageId, messageId));
    }

    /** 按会话分页列出精华，按标记时间倒序（最新加的精华排最前）。 */
    default IPage<MessageEssence> pageByConversation(long current, long size, Long conversationId) {
        return selectPage(Page.of(current, size), Wrappers.<MessageEssence>lambdaQuery()
                .eq(MessageEssence::getConversationId, conversationId)
                .orderByDesc(MessageEssence::getCreateTime)
                .orderByDesc(MessageEssence::getId));
    }

    /** 物理清理一批消息的全部精华行，供「账号注销 / 数据删除」等级联删除复用。 */
    default int deleteByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return delete(Wrappers.<MessageEssence>lambdaQuery()
                .in(MessageEssence::getMessageId, messageIds));
    }
}
