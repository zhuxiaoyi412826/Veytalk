package com.im.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.im.common.config.ImProperties;
import com.im.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * 三级缓存：L1 Caffeine（JVM 本地）→ L2 Redis（分布式）→ L3 数据库。
 *
 * <p>读路径（对应架构图的 alt 嵌套）：L1 命中直接返回；L1 未命中查 L2，命中则回写 L1；
 * L2 也未命中才执行 loader 回源数据库，结果逐层回填。写路径：业务更新数据库后调用
 * {@link #evict}（L1+L2 一起失效），而不是回填——回填值要和并发写赛跑，容易把旧值写回去。
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li><b>空值哨兵防穿透</b>：loader 返回 null 也会记一笔（L2 写 {@code __NULL__}），
 *       恶意/异常 id 不会每次都打到数据库；哨兵 TTL 很短（{@code im.cache.null-ttl-seconds}），
 *       新建数据最多隐身这么久而已。</li>
 *   <li><b>Redis 不可用自动降级</b>：与 {@code RateLimiter} 同样的
 *       {@code ObjectProvider} 模式，L2 读写全部 try/catch，Redis 挂了缓存退化为
 *       「L1 + 数据库」两级，功能不受影响，只是回源变多。</li>
 *   <li><b>多节点不做强一致</b>：evict 只失效本节点 L1，其他节点靠 L1 短 TTL 收敛。
 *       资料类数据的脏读窗口以分钟计，不值得为它引入 pub/sub 广播的复杂度。</li>
 * </ul>
 *
 * <p>缓存值以 JSON 字符串存进 Redis（复用 {@link JsonUtil} 的全局 ObjectMapper），
 * 与项目里其他 Redis 用法保持一致，避免 JDK 序列化跨版本读取失败。
 */
@Slf4j
@Component
public class ThreeLevelCache {

    /** L1 里代表「已确认数据库没有这个 key」的哨兵（Caffeine 不允许存 null） */
    private static final Object NULL_MARKER = new Object();
    /** L2 里的空值哨兵字符串 */
    private static final String L2_NULL = "__NULL__";
    /** L2 key 统一前缀，与在线状态、限流等其他用途的 key 隔离，方便按前缀排查 */
    private static final String L2_PREFIX = "im:cache:";

    private final ImProperties properties;
    private final JsonUtil jsonUtil;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Cache<String, Object> l1;

    public ThreeLevelCache(ImProperties properties, JsonUtil jsonUtil,
                           ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.jsonUtil = jsonUtil;
        this.redisProvider = redisProvider;
        ImProperties.Cache cfg = properties.getCache();
        this.l1 = Caffeine.newBuilder()
                .maximumSize(cfg.getL1MaxSize())
                .expireAfterWrite(Duration.ofSeconds(cfg.getL1TtlSeconds()))
                .build();
    }

    /**
     * 读穿透：逐层找，全未命中才执行 loader 并回填。
     *
     * <p>L2 的 TTL 用配置默认值；loader 返回 null 时缓存空值哨兵。
     */
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        return get(key, type, null, loader);
    }

    /**
     * @param l2Ttl L2 过期时间，null 表示用 {@code im.cache.l2-ttl-seconds} 默认值
     */
    public <T> T get(String key, Class<T> type, Duration l2Ttl, Supplier<T> loader) {
        if (!properties.getCache().isEnabled()) {
            return loader.get();
        }
        Object hit = readL1L2(key, type);
        if (hit == NULL_MARKER) {
            return null;
        }
        if (hit != null) {
            return type.cast(hit);
        }
        T value = loader.get();
        put(key, value, l2Ttl);
        return value;
    }

    /**
     * 只查 L1/L2、不回源数据库；未命中或命中空值哨兵都返回 null。
     *
     * <p>给批量场景用：先逐个 peek 收集未命中的 id，再一次 {@code selectByIds} 回源，
     * 避免退化成 N 次单行查询。
     */
    public <T> T peek(String key, Class<T> type) {
        if (!properties.getCache().isEnabled()) {
            return null;
        }
        Object hit = readL1L2(key, type);
        return hit == null || hit == NULL_MARKER ? null : type.cast(hit);
    }

    /**
     * 回填 L1+L2；value 为 null 时写空值哨兵（短 TTL）。
     * {@link #get} 内部已调用，主要供批量回源后逐条回填使用。
     */
    public <T> void put(String key, T value, Duration l2Ttl) {
        ImProperties.Cache cfg = properties.getCache();
        if (!cfg.isEnabled()) {
            return;
        }
        l1.put(key, value == null ? NULL_MARKER : value);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        Duration ttl = value == null
                ? Duration.ofSeconds(cfg.getNullTtlSeconds())
                : (l2Ttl != null ? l2Ttl : Duration.ofSeconds(cfg.getL2TtlSeconds()));
        try {
            String json = value == null ? L2_NULL : jsonUtil.toJson(value);
            if (json != null) {
                redis.opsForValue().set(L2_PREFIX + key, json, ttl);
            }
        } catch (Exception e) {
            log.warn("缓存写 L2 失败，降级为仅 L1: key={}, err={}", key, e.getMessage());
        }
    }

    /** 失效一个 key（L1+L2）。业务更新数据库之后必须调用，否则脏到 L2 TTL 到期 */
    public void evict(String key) {
        l1.invalidate(key);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(L2_PREFIX + key);
        } catch (Exception e) {
            log.warn("失效 L2 失败（L1 已失效，靠 TTL 收敛）: key={}, err={}", key, e.getMessage());
        }
    }

    public void evict(Collection<String> keys) {
        keys.forEach(this::evict);
    }

    /**
     * L1 → L2 顺序读；L2 命中回写 L1。返回 {@link #NULL_MARKER} 表示命中空值哨兵，
     * 返回 null 表示两层都未命中。
     */
    private Object readL1L2(String key, Class<?> type) {
        Object local = l1.getIfPresent(key);
        if (local != null) {
            return local;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        String json;
        try {
            json = redis.opsForValue().get(L2_PREFIX + key);
        } catch (Exception e) {
            log.warn("读 L2 失败，回源数据库: key={}, err={}", key, e.getMessage());
            return null;
        }
        if (json == null) {
            return null;
        }
        if (L2_NULL.equals(json)) {
            l1.put(key, NULL_MARKER);
            return NULL_MARKER;
        }
        // 反序列化失败按未命中处理（比如 DTO 字段变更后残留的旧格式数据），回源会覆盖写
        Object parsed = jsonUtil.fromJsonQuietly(json, type);
        if (parsed == null) {
            return null;
        }
        l1.put(key, parsed);
        return parsed;
    }
}
