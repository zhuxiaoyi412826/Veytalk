package com.im.message.service.impl;

import com.im.common.constant.RedisKeys;
import com.im.common.util.RedisUtil;
import com.im.message.mapper.MessageMapper;
import com.im.message.service.MessageSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Redis {@code INCR} 的发号器实现。
 *
 * <p>为什么不用数据库自增列：{@code seq} 只在会话内递增，MySQL 没有「分组自增」能力，
 * 要么给每个会话建一张计数表（写放大），要么 {@code SELECT MAX(seq)+1}（并发下必然撞号）。
 * Redis 的 {@code INCR} 单线程原子，天然满足需求，且省掉一次数据库往返。
 *
 * <h2>计数器与数据库的对齐</h2>
 *
 * <p>计数器是缓存，数据库才是权威。两者一旦分叉，分叉的方向只会是「计数器落后」，
 * 而落后的计数器会把已经用过的号重新发一遍——{@code idx_conv_seq} 是非唯一索引，
 * 数据库不会拦，前端也看不出来，等到有人发现聊天记录顺序错乱时，重复行已经落库了。
 * 因此每个会话在本进程内第一次发号时，都要用 {@code SELECT MAX(seq)} 把计数器抬到位
 * （{@link RedisUtil#raiseToAtLeast}，只增不减，不会把并发 {@code INCR} 的成果改小）。
 *
 * <p>这一步覆盖三种「计数器落后」的来源：
 * <ol>
 *   <li>键缺失：Redis 重启、被 {@code FLUSHDB}、或被 {@code maxmemory} 淘汰。
 *       此时若直接 {@code INCR}，Redis 会隐式建键并从 1 开始，与全部历史消息撞号，
 *       所以热路径用的是 {@link RedisUtil#incrementIfPresent}——键不在就返回 {@code null}
 *       走对齐分支，而不是当成 0。</li>
 *   <li>键存在但值偏小，因为降级取号期间发出的号没写回计数器（见下）。</li>
 *   <li>键存在但值偏小，因为有人绕过应用直接往库里写了消息：手工修数据、
 *       {@code mysqlbinlog} 前像恢复、导入 SQL 脚本都属于这一类。
 *       <strong>这类改动之后重启后端即可自愈</strong>；不方便重启就手工
 *       {@code SET im:conv:seq:{conversationId}} 到库里的 {@code MAX(seq)}。</li>
 * </ol>
 *
 * <p>对齐是「每会话每进程一次」而不是每条消息一次：{@code MAX(seq)} 走
 * {@code idx_conv_seq} 是最右端查找，很便宜，但给每条消息都加一次数据库往返，
 * 就把「用 Redis 发号省掉一次往返」的收益全吃掉了。
 *
 * <h2>Redis 不可用时的降级</h2>
 *
 * <p>降级为 {@code SELECT MAX(seq)+1}，此时并发发送理论上可能撞号，
 * 但「Redis 挂了」本身已经是需要告警的故障，保住消息能发出去比保住严格递增更重要。
 * 降级路径必须做三件事，少做任何一件都会留下重复 seq，详见
 * {@link #nextSeqFromDatabase}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageSequenceServiceImpl implements MessageSequenceService {

    /**
     * 跟踪的会话数上限。
     *
     * <p>活跃会话数正常远小于它；超限直接整表清掉重新对齐，代价只是每会话多一次
     * {@code MAX(seq)} 查询，换来的是不会被伪造的会话 ID 撑爆内存。
     * 清表期间两个线程可能同时对同一会话做对齐，但 {@code raiseToAtLeast} 只增不减、
     * {@code INCR} 本身原子，两边拿到的号依然不会重复。
     */
    private static final int MAX_TRACKED_CONVERSATIONS = 50_000;

    private final RedisUtil redisUtil;
    private final MessageMapper messageMapper;

    private final ConcurrentHashMap<Long, ConversationState> states = new ConcurrentHashMap<>();

    @Override
    public long nextSeq(Long conversationId) {
        String key = RedisKeys.convSeq(conversationId);
        ConversationState state = stateOf(conversationId);
        try {
            // 热路径只有一次 Redis 往返：不查库、不加锁
            Long seq = null;
            if (!state.needsAlign()) {
                seq = redisUtil.incrementIfPresent(key);
            }
            if (seq == null) {
                // 本进程首次为该会话发号，或计数器键在 Redis 里不见了，都要先对齐再取号
                seq = alignAndIncrement(key, conversationId, state);
            }
            return seq;
        } catch (Exception e) {
            log.warn("[消息发号] Redis 不可用，降级为数据库取号: conversationId={}, {}", conversationId, e.getMessage());
            return nextSeqFromDatabase(key, conversationId, state);
        }
    }

    /**
     * 用数据库的 {@code MAX(seq)} 校正计数器，然后取号。
     *
     * <p>整段放在会话锁内：校正与自增之间若被别的线程插入一次「键缺失 → 从 1 开始」
     * 的自增，撞号就已经发生了。锁只覆盖单实例，多实例之间靠
     * {@code raiseToAtLeast} 的「只增不减」语义保证谁先谁后都不会把值改小。
     */
    private long alignAndIncrement(String key, Long conversationId, ConversationState state) {
        synchronized (state) {
            long dbMax = messageMapper.selectMaxSeq(conversationId);
            long aligned = redisUtil.raiseToAtLeast(key, dbMax);
            state.markAligned();
            long seq = redisUtil.increment(key);
            if (aligned > dbMax) {
                log.info("[消息发号] 会话计数器已领先数据库，保持不变: conversationId={}, dbMax={}, 计数器={}, 取号={}",
                        conversationId, dbMax, aligned, seq);
            } else {
                log.info("[消息发号] 会话计数器与数据库对齐: conversationId={}, 起始值={}, 取号={}",
                        conversationId, dbMax, seq);
            }
            return seq;
        }
    }

    /**
     * Redis 不可用时的降级取号。
     *
     * <p>三件事必须一起做，缺一件就会留下重复 seq：
     * <ol>
     *   <li><b>串行化</b>：{@code SELECT MAX(seq)+1} 自身没有互斥，同实例并发发送会算出
     *       同一个号。锁只能覆盖单实例；多实例同时降级仍可能撞号，那属于
     *       「Redis 挂了」这个故障本身要告警的范围。</li>
     *   <li><b>把结果回写计数器</b>：这是重复 seq 的直接根因——旧实现取完号就返回，
     *       计数器停在降级前的旧值上，Redis 一恢复，下一次 {@code INCR} 就把
     *       降级期间已经用过的号又发了一遍。</li>
     *   <li><b>回写失败时撤掉对齐标记</b>：Redis 还没恢复就写不进去，那就让恢复后的
     *       第一次取号重新与数据库对齐，而不是继续拿着旧计数器发号。</li>
     * </ol>
     *
     * <p>降级期间每条消息会比正常路径多一次 {@code MAX(seq)}：对齐分支已经查过一次库，
     * 失败后这里再查一次。这是刻意接受的——Redis 整体不可用时应用早已全面降级
     * （消息幂等标记同样退化为只靠唯一键），一次索引最右端查找在这种故障下是噪声。
     */
    private long nextSeqFromDatabase(String key, Long conversationId, ConversationState state) {
        synchronized (state) {
            long seq = messageMapper.selectMaxSeq(conversationId) + 1;
            try {
                redisUtil.raiseToAtLeast(key, seq);
                state.markAligned();
            } catch (Exception e) {
                state.markDegraded();
                log.warn("[消息发号] 降级号回写 Redis 失败，恢复后将重新对齐: conversationId={}, seq={}, {}",
                        conversationId, seq, e.getMessage());
            }
            return seq;
        }
    }

    private ConversationState stateOf(Long conversationId) {
        if (states.size() > MAX_TRACKED_CONVERSATIONS) {
            states.clear();
        }
        return states.computeIfAbsent(conversationId, id -> new ConversationState());
    }

    /**
     * 每会话的进程内状态。
     *
     * <p>同时充当两个角色：{@code aligned} 标记「本进程是否已把计数器与数据库对齐过」，
     * 对象本身是对齐与降级取号的互斥锁。合成一个类是为了只维护一张 map——
     * 两张 map 的容量上限和清扫逻辑要各写一遍，还容易改了一张忘了另一张。
     */
    private static final class ConversationState {

        /** 新建时为 false；降级回写失败后重新置 false，等 Redis 恢复再校正一次 */
        private volatile boolean aligned;

        boolean needsAlign() {
            return !aligned;
        }

        void markAligned() {
            aligned = true;
        }

        void markDegraded() {
            aligned = false;
        }
    }
}
