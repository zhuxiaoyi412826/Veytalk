package com.im.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.message.entity.MessageFavorite;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 消息收藏 Mapper。
 *
 * <p>两个读取方向，与 {@link MessageEssenceMapper} 同构：随消息列表批量判定「哪些条我收藏过」
 * （{@link #selectByMessageIds}，整页一次取行、内存比对），以及「按用户列出全部收藏」
 * （{@link #pageByUser}，供收藏面板分页浏览，可选按会话过滤）。
 * 增删走 {@code BaseMapper} 的 insert / delete。
 *
 * <p>所有查询都带 {@code user_id}：收藏是私人数据，漏掉这个条件等于把别人的收藏列表发出去。
 */
@Mapper
public interface MessageFavoriteMapper extends BaseMapper<MessageFavorite> {

    /** 查出这批消息中当前用户收藏了哪些（返回对应行），供调用方按 messageId 集合标记 VO.favorite。 */
    default List<MessageFavorite> selectByMessageIds(Long userId, Collection<Long> messageIds) {
        if (userId == null || messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId)
                .in(MessageFavorite::getMessageId, messageIds));
    }

    /** 当前用户是否已收藏某条消息。 */
    default boolean existsByMessage(Long userId, Long messageId) {
        return selectCount(Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId)
                .eq(MessageFavorite::getMessageId, messageId)) > 0;
    }

    /** 取消收藏：只删本人那一行，别人对同一条消息的收藏不受影响。删 0 行不报错，幂等。 */
    default int deleteByMessageId(Long userId, Long messageId) {
        return delete(Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId)
                .eq(MessageFavorite::getMessageId, messageId));
    }

    /**
     * 按用户分页列出收藏，按收藏时间倒序（最新收藏的排最前）。
     *
     * <p>{@code conversationId} 为空即跨全部会话，非空只看该会话——同一份面板的两种视图，
     * 不值得为它单开一个接口。走 {@code idx_user_time} 索引，排序再补一个 id 兜底：
     * 同一秒内连续收藏时，只有 id 能给出稳定顺序，否则翻页会看到重复/漏项。
     */
    default IPage<MessageFavorite> pageByUser(long current, long size, Long userId, Long conversationId) {
        return selectPage(Page.of(current, size), Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId)
                .eq(conversationId != null, MessageFavorite::getConversationId, conversationId)
                .orderByDesc(MessageFavorite::getCreateTime)
                .orderByDesc(MessageFavorite::getId));
    }

    /** 清空某会话聊天记录时的级联：这个人在该会话里的收藏一并撤掉（收藏的行点不开消息，留着只会是幽灵行）。 */
    default int deleteByConversation(Long userId, Long conversationId) {
        return delete(Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId)
                .eq(MessageFavorite::getConversationId, conversationId));
    }

    /** 物理清理一批消息的全部收藏行，供「账号注销 / 数据删除」等级联删除复用。 */
    default int deleteByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return delete(Wrappers.<MessageFavorite>lambdaQuery()
                .in(MessageFavorite::getMessageId, messageIds));
    }
}
