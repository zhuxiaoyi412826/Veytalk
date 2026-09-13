package com.im.message.service.impl;

import com.im.common.constant.RedisKeys;
import com.im.common.util.RedisUtil;
import com.im.message.mapper.MessageMapper;
import com.im.message.service.MessageSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis {@code INCR} 的发号器实现。
 *
 * <p>为什么不用数据库自增列：{@code seq} 只在会话内递增，MySQL 没有「分组自增」能力，
 * 要么给每个会话建一张计数表（写放大），要么 {@code SELECT MAX(seq)+1}（并发下必然撞号）。
 * Redis 的 {@code INCR} 单线程原子，天然满足需求，且省掉一次数据库往返。
 *
 * <p>计数器初始化用 {@code SETNX} 而不是 {@code SET}：Redis 重启或 key 被清空后，
 * 多个发送请求会同时发现计数器缺失，若都用 {@code SET} 写初值，后写的会覆盖先写的，
 * 紧接着的两次 {@code INCR} 就会拿到相同的号。{@code SETNX} 保证只有第一个请求写入成功，
 * 其余请求直接读到同一个初值。
 *
 * <p>Redis 彻底不可用时降级为 {@code SELECT MAX(seq)+1}：此时并发发送理论上可能撞号，
 * 但「Redis 挂了」本身已经是需要告警的故障，保住消息能发出去比保住严格递增更重要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageSequenceServiceImpl implements MessageSequenceService {

    private final RedisUtil redisUtil;
    private final MessageMapper messageMapper;

    @Override
    public long nextSeq(Long conversationId) {
        String key = RedisKeys.convSeq(conversationId);
        try {
            ensureInitialized(key, conversationId);
            return redisUtil.increment(key);
        } catch (Exception e) {
            log.warn("[消息发号] Redis 不可用，降级为数据库取号: conversationId={}, {}", conversationId, e.getMessage());
            return messageMapper.selectMaxSeq(conversationId) + 1;
        }
    }

    /**
     * 计数器缺失时用数据库已有最大 seq 落初值；计数器永不过期，因此只在缺失时查一次库。
     */
    private void ensureInitialized(String key, Long conversationId) {
        if (redisUtil.hasKey(key)) {
            return;
        }
        long dbMax = messageMapper.selectMaxSeq(conversationId);
        if (redisUtil.setIfAbsent(key, String.valueOf(dbMax))) {
            log.info("[消息发号] 初始化会话计数器: conversationId={}, 起始值={}", conversationId, dbMax);
        }
    }
}
