package com.im.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 工具，统一基于 {@link StringRedisTemplate}，对象以 JSON 字符串存储，
 * 避免不同模块序列化器不一致导致的读取失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUtil {

    /**
     * 计数器存在才 INCR，键缺失时返回 {@code null} 而不是从 0 开始自增。
     *
     * <p>为什么不能直接 INCR：INCR 对不存在的键会隐式建键并从 1 开始。
     * 对于「计数器落后于权威数据就会出事」的场景（如会话 seq），
     * 键被 FLUSHDB 或 maxmemory 淘汰后直接 INCR 等于把已经用过的号重新发一遍。
     * 先查存在再自增必须在 Redis 内原子完成，否则两步之间键刚好过期依然会撞号。
     */
    private static final DefaultRedisScript<Long> INCR_IF_PRESENT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return nil end "
                    + "return redis.call('INCR', KEYS[1])",
            Long.class);

    /**
     * 把计数器抬到不小于指定值，只增不减。
     *
     * <p>用于「缓存里的计数器可能落后于数据库」的校正：直接 {@code SET} 会把并发
     * {@code INCR} 已经推进的值改小，紧接着就会发出重复的号；先 {@code GET} 再比较后
     * {@code SET} 又不是原子的。两步放进 Lua 才能既校正又不回退。
     * 写入不带 TTL，与 {@link #setIfAbsent(String, String)} 的语义保持一致。
     */
    private static final DefaultRedisScript<Long> RAISE_TO_AT_LEAST = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "local floor = tonumber(ARGV[1]) "
                    + "if floor > cur then redis.call('SET', KEYS[1], floor) return floor end "
                    + "return cur",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonUtil jsonUtil;

    /* ==================== String ==================== */

    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public void setObject(String key, Object value, Duration ttl) {
        String json = jsonUtil.toJson(value);
        if (json == null) {
            return;
        }
        set(key, json, ttl);
    }

    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public <T> T getObject(String key, Class<T> clazz) {
        return jsonUtil.fromJsonQuietly(get(key), clazz);
    }

    /**
     * 仅当 key 不存在时写入，用于幂等与频率限制。
     *
     * @return 写入成功返回 true
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 仅当 key 不存在时写入且不设过期时间。
     *
     * <p>会话 seq 计数器这类数据一旦过期就会与历史消息撞号，必须永久保留，
     * 因此单独提供不带 TTL 的重载，避免调用方误传 {@code null} 触发 NPE。
     *
     * @return 写入成功返回 true
     */
    public boolean setIfAbsent(String key, String value) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value);
        return Boolean.TRUE.equals(ok);
    }

    public long increment(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        return value == null ? 0L : value;
    }

    public long increment(String key, long delta) {
        Long value = redisTemplate.opsForValue().increment(key, delta);
        return value == null ? 0L : value;
    }

    /**
     * 仅当计数器已存在时自增，键缺失时返回 {@code null}。
     *
     * <p>调用方拿到 {@code null} 应该先用权威数据（如 {@code SELECT MAX(seq)}）把计数器
     * 播种到位再自增，而不是把它当成 0 直接用。
     */
    public Long incrementIfPresent(String key) {
        return redisTemplate.execute(INCR_IF_PRESENT, List.of(key));
    }

    /**
     * 原子地把计数器抬到不小于 {@code floor}，已经更大时保持不变。
     *
     * @return 校正之后的计数器值
     */
    public long raiseToAtLeast(String key, long floor) {
        Long value = redisTemplate.execute(RAISE_TO_AT_LEAST, List.of(key), String.valueOf(floor));
        // execute 返回 null 只出现在管道/事务上下文，此时无法读到真实值，按 floor 回报
        return value == null ? floor : value;
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public long delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        Long count = redisTemplate.delete(keys);
        return count == null ? 0L : count;
    }

    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, ttl));
    }

    public long getExpire(String key) {
        Long seconds = redisTemplate.getExpire(key);
        return seconds == null ? -2L : seconds;
    }

    public Set<String> keys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys == null ? Collections.emptySet() : keys;
    }

    /* ==================== Hash ==================== */

    public void hSet(String key, String hashKey, String value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    public void hSetAll(String key, Map<String, String> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    public Object hGet(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    public long hDelete(String key, Object... hashKeys) {
        return redisTemplate.opsForHash().delete(key, hashKeys);
    }

    public boolean hHasKey(String key, String hashKey) {
        return redisTemplate.opsForHash().hasKey(key, hashKey);
    }

    /* ==================== Set ==================== */

    public long sAdd(String key, String... values) {
        Long count = redisTemplate.opsForSet().add(key, values);
        return count == null ? 0L : count;
    }

    public Set<String> sMembers(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members == null ? Collections.emptySet() : members;
    }

    public boolean sIsMember(String key, String value) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, value));
    }

    public long sRemove(String key, Object... values) {
        Long count = redisTemplate.opsForSet().remove(key, values);
        return count == null ? 0L : count;
    }

    /* ==================== List ==================== */

    public long lPush(String key, String value) {
        Long size = redisTemplate.opsForList().leftPush(key, value);
        return size == null ? 0L : size;
    }

    public List<String> lRange(String key, long start, long end) {
        List<String> list = redisTemplate.opsForList().range(key, start, end);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 删除并返回键对应的值，用于一次性票据。
     */
    public String getAndDelete(String key) {
        String value = get(key);
        if (value != null) {
            delete(key);
        }
        return value;
    }
}
