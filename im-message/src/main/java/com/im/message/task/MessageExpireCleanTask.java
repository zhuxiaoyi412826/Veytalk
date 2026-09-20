package com.im.message.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.config.ImProperties;
import com.im.message.entity.MessageDelete;
import com.im.message.entity.MessageRead;
import com.im.message.mapper.MessageDeleteMapper;
import com.im.message.mapper.MessageMapper;
import com.im.message.mapper.MessageReadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息过期清理任务。
 *
 * <p>{@code im_message} 同时充当离线消息的持久化队列，只增不减终将拖垮查询与存储。
 * 保留天数由 {@code im.message.retention-days} 控制，默认 0 表示永不清理——
 * 聊天记录的删除必须显式开启，不能让用户在不知情的情况下丢历史。
 *
 * <p>删除按「ID 分批 + 三表级联」推进：先取一批过期消息主键，再删
 * {@code im_message_read}、{@code im_message_delete} 两张从表和主表。
 * 逐条消息各删一次会产生 3N 次往返，一次 {@code IN} 批量删除把每条 SQL 的开销
 * 收敛为一批一次；批次上限防止单次事务膨胀到大段索引锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageExpireCleanTask {

    /** 单批删除的消息数上限 */
    private static final int BATCH_SIZE = 1000;

    /** 单次调度的批次上限，防止历史欠账太多时一次跑几个小时 */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final MessageMapper messageMapper;
    private final MessageReadMapper messageReadMapper;
    private final MessageDeleteMapper messageDeleteMapper;
    private final ImProperties imProperties;

    /**
     * 每天凌晨 3:30 清理过期消息，低峰期执行且与心跳、序列等任务错开。
     *
     * <p>{@code cron} 可通过 {@code im.message.clean-cron} 覆盖；多实例部署时各节点
     * 会各自触发，但删除以主键 {@code IN} 为条件天然幂等，重复执行只是空转不会误删。
     */
    @Scheduled(cron = "${im.message.clean-cron:0 30 3 * * ?}")
    public void cleanExpiredMessages() {
        int retentionDays = imProperties.getMessage().getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<Long> ids = messageMapper.selectExpiredIds(beforeTime, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            // 先删从表再删主表：中途失败时主表还在，下一轮调度会重新级联，不产生孤儿行
            messageReadMapper.delete(Wrappers.<MessageRead>lambdaQuery()
                    .in(MessageRead::getMessageId, ids));
            messageDeleteMapper.delete(Wrappers.<MessageDelete>lambdaQuery()
                    .in(MessageDelete::getMessageId, ids));
            messageMapper.deleteByIds(ids);
            total += ids.size();
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("[消息清理] 保留 {} 天，本次物理删除过期消息 {} 条（含回执与单端删除记录）", retentionDays, total);
        }
    }
}
