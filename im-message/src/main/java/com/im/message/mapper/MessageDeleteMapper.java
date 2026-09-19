package com.im.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.message.entity.MessageDelete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息单端删除 Mapper。
 *
 * <p>删除记录只在渲染历史消息时用于「排除」，因此查询接口一律返回 ID 集合，
 * 调用方在内存里做一次 {@code contains} 过滤即可，不必回表取整行。
 */
@Mapper
public interface MessageDeleteMapper extends BaseMapper<MessageDelete> {

    /**
     * 查出这批消息中已被该用户删除的消息 ID。
     */
    default Set<Long> selectDeletedIds(Long userId, Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Set.of();
        }
        return selectList(Wrappers.<MessageDelete>lambdaQuery()
                        .select(MessageDelete::getMessageId)
                        .eq(MessageDelete::getUserId, userId)
                        .in(MessageDelete::getMessageId, messageIds))
                .stream()
                .map(MessageDelete::getMessageId)
                .collect(Collectors.toSet());
    }

    /**
     * 某用户删除过的全部消息 ID，用于离线消息拉取时过滤。
     */
    default Set<Long> selectDeletedIdsByUser(Long userId) {
        List<MessageDelete> records = selectList(Wrappers.<MessageDelete>lambdaQuery()
                .select(MessageDelete::getMessageId)
                .eq(MessageDelete::getUserId, userId));
        return records.stream().map(MessageDelete::getMessageId).collect(Collectors.toSet());
    }

    /**
     * 是否已被该用户删除，用于重复删除时直接返回成功。
     */
    default boolean exists(Long userId, Long messageId) {
        return selectCount(Wrappers.<MessageDelete>lambdaQuery()
                .eq(MessageDelete::getUserId, userId)
                .eq(MessageDelete::getMessageId, messageId)) > 0;
    }

    /**
     * 批量写入单端删除记录，供「清空会话聊天记录」一次插入多行。
     *
     * <p>{@code id} 由调用方用雪花算法填好：批量 insert 语句不走 MyBatis-Plus 的 id 自动填充，
     * 留空会因主键为 null 直接报错。调用方需自行保证不违反唯一键 {@code uk_msg_user}。
     */
    @Insert("""
            <script>
            INSERT INTO im_message_delete (id, message_id, user_id, create_time)
            VALUES
            <foreach collection="list" item="item" separator=",">
                (#{item.id}, #{item.messageId}, #{item.userId}, #{item.createTime})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") Collection<MessageDelete> list);
}
