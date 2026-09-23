package com.im.common.security.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固定窗口计数器，HTTP 拦截器与 WebSocket 通道共用的限流内核。
 *
 * <h2>为什么是固定窗口而不是滑动窗口/令牌桶</h2>
 *
 * <p>限流在这里的目标是「挡住脚本刷接口」，不是「精确整形流量」：
 * 固定窗口的最坏情况是窗口交界处放行两倍配额，对防爆破场景毫无影响，
 * 而它的实现只需要一次 INCR——滑动窗口要存每次请求的时间戳（ZSET），
 * 令牌桶要维护补充时刻，两者的 Redis 开销和代码量都数倍于此。
 *
 * <h2>Redis 优先，内存兜底</h2>
 *
 * <p>计数优先落 Redis：多实例部署时所有节点共享同一份配额，
 * 攻击者不能靠轮询不同节点把限额乘上节点数。Redis 不可用时退化到进程内计数并放行失败——
 * <strong>fail-open</strong> 是刻意选择：限流是护栏而不是闸门，
 * Redis 抖动几秒钟就把全站接口打成 429，故障面远大于它挡下的那点滥用；
 * 真正的登录防爆破还有图形验证码这道独立闸门兜着。
 */
@Slf4j
@Component
public class RateLimiter {

    /** INCR + 首次设置过期，两步在 Redis 内原子完成，不会出现「计数在、过期没设上」的永久键 */
    private static final DefaultRedisScript<Long> INCR_EXPIRE = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    /** Redis 键前缀，与在线状态、验证码等既有键空间隔开 */
    private static final String KEY_PREFIX = "im:ratelimit:";

    /**
     * 进程内兜底窗口：key -> [窗口起点毫秒, 窗口内计数]。
     * 键数量以「端点数 × 活跃用户/IP 数」为上界，正常规模下不足为患；
     * 超过 {@link #LOCAL_MAX_KEYS} 时做一次整表过期清扫，防御被伪造维度值撑爆。
     */
    private final ConcurrentHashMap<String, long[]> localWindows = new ConcurrentHashMap<>();

    private static final int LOCAL_MAX_KEYS = 20_000;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * 尝试取得一次配额。
     *
     * @param name    计数器名称（端点标识）
     * @param id      维度值（IP 或 userId）
     * @param count   窗口内允许的最大次数，&lt;=0 视为不限
     * @param seconds 窗口长度（秒），&lt;=0 视为不限
     * @return {@code true} 放行；{@code false} 超限
     */
    public boolean tryAcquire(String name, String id, int count, int seconds) {
        if (count <= 0 || seconds <= 0) {
            return true;
        }
        String key = KEY_PREFIX + name + ":" + id;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Long current = redis.execute(INCR_EXPIRE, List.of(key), String.valueOf(seconds));
                // execute 返回 null 只出现在管道/事务上下文，按未超限处理
                return current == null || current <= count;
            } catch (Exception e) {
                // fail-open，但退到内存窗口继续限，而不是彻底裸奔
                log.warn("限流计数器 Redis 不可用，退化为进程内计数: key={}, err={}", key, e.getMessage());
            }
        }
        return tryAcquireLocal(key, count, seconds);
    }

    /** 进程内固定窗口。窗口对齐到秒级时间轴，同一窗口内所有请求落在同一个数组上 */
    private boolean tryAcquireLocal(String key, int count, int seconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - now % (seconds * 1000L);
        if (localWindows.size() > LOCAL_MAX_KEYS) {
            localWindows.entrySet().removeIf(entry -> entry.getValue()[0] < now - 600_000L);
        }
        long[] window = localWindows.computeIfAbsent(key, k -> new long[]{windowStart, 0});
        synchronized (window) {
            if (window[0] != windowStart) {
                window[0] = windowStart;
                window[1] = 0;
            }
            window[1]++;
            return window[1] <= count;
        }
    }
}
