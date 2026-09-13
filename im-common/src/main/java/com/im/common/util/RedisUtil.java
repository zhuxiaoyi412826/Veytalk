package com.im.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
