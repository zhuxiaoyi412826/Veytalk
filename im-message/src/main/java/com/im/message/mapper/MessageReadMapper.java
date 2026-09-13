package com.im.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.message.entity.MessageRead;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 消息送达 / 已读回执 Mapper。
 */
@Mapper
public interface MessageReadMapper extends BaseMapper<MessageRead> {

    /**
     * 批量写入回执，命中唯一键 {@code uk_msg_user} 时只补齐缺失的时间点。
     *
     * <p>用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查再决定 insert 还是 update」：
     * 一次已读上报可能覆盖上百条消息，逐条读写会产生两倍以上的往返；
     * {@code COALESCE} 保证已有的时间点不会被后到的 {@code null} 覆盖掉，
     * 例如「先收到已读上报、后收到延迟送达的送达上报」这种乱序场景。
     *
     * <p>主键由调用方通过 {@code IdWorker} 生成——{@code im_message_read.id} 不是自增列，
     * 且 MyBatis-Plus 的自动填充只对它自己的 {@code insert} 方法生效，自定义 SQL 需要手工赋值。
     */
    @Insert("""
            <script>
                INSERT INTO im_message_read (id, message_id, user_id, delivered_time, read_time, create_time)
                VALUES
                <foreach collection="records" item="item" separator=",">
                    (#{item.id}, #{item.messageId}, #{item.userId},
                     #{item.deliveredTime,jdbcType=TIMESTAMP}, #{item.readTime,jdbcType=TIMESTAMP}, #{item.createTime})
                </foreach>
                ON DUPLICATE KEY UPDATE
                    delivered_time = COALESCE(delivered_time, VALUES(delivered_time)),
                    read_time      = COALESCE(read_time, VALUES(read_time))
            </script>
            """)
    int upsertBatch(@Param("records") Collection<MessageRead> records);

    /**
     * 批量查询若干消息的全部回执，用于给「我发出的消息」计算已送达 / 已读状态。
     */
    default List<MessageRead> selectByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageRead>lambdaQuery()
                .in(MessageRead::getMessageId, messageIds));
    }

    /**
     * 查询某用户对若干消息已有的回执，用于送达 / 已读上报时跳过重复写入。
     */
    default List<MessageRead> selectByUserAndMessageIds(Long userId, Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MessageRead>lambdaQuery()
                .eq(MessageRead::getUserId, userId)
                .in(MessageRead::getMessageId, messageIds));
    }
}
