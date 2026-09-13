package com.im.common.security;

import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 登录态存储的 Redis 5 兼容实现。
 *
 * <p>父类 {@link SaTokenDaoForRedisTemplate#setStringAndKeepTTL} 下发的是
 * {@code SET key value KEEPTTL XX}，而 {@code KEEPTTL} 是 Redis 6.0 才引入的参数。
 * 在 Redis 5 上服务端直接回 {@code ERR syntax error}，Lettuce 抛
 * {@code RedisCommandExecutionException}，一路冒泡成登录接口 500 —— 不是某个功能降级，
 * 而是所有需要写入会话的动作（登录、续期、多端登记）全部不可用。
 * Windows 上广泛使用的 tporadowski 移植版正停在 5.0.14，所以这里做兼容而不是要求换环境。
 *
 * <p><b>生效前提</b>：{@code application.yml} 中把父类从自动配置里排除了
 * （{@code spring.autoconfigure.exclude}）。两处是成对的 —— 少了排除，容器里会同时存在
 * 两个 {@code SaTokenDao}，而 Sa-Token 的注入点是单参数 {@code @Autowired(required = false)}，
 * {@code required = false} 只解决「一个都没有」的情况，消不掉「有两个」的歧义，
 * 启动会以 {@code NoUniqueBeanDefinitionException} 失败。
 *
 * <p>审计过本项目的其余 Redis 用法（{@code RedisUtil} 与 Sa-Token 自身的其他方法），
 * 只用到 SET/GET/DEL/INCR/EXPIRE/TTL/SCAN/HSET/SADD/LPUSH 这类古老命令，
 * {@code RedisUtil#getAndDelete} 也是手写 get + delete 而非 6.2 的 GETDEL，
 * 所以不兼容的点只有这一处。
 *
 * <p>本实现在 Redis 6/7 上同样正确，代价只是把一条命令换成同一连接上的两条。
 * 若将来确认部署环境全部不低于 6.0，删掉本类并去掉那条 exclude 即可。
 */
@Slf4j
@Component
public class SaTokenDaoForLegacyRedis extends SaTokenDaoForRedisTemplate {

    /** {@code PTTL} 对「键存在但没有设置过期时间」的返回值，与 {@code SaTokenDao.NEVER_EXPIRE} 一致。 */
    private static final long PERSISTENT = -1L;

    /** {@code PTTL} 对「键不存在」的返回值，与 {@code SaTokenDao.NOT_VALUE_EXPIRE} 一致。 */
    private static final long ABSENT = -2L;

    /**
     * 父类在 {@code init} 末尾留的钩子，只在这里确认一次兼容模式已装配。
     * 这条日志值得留：出问题时它能直接说明当前走的是哪套实现。
     */
    @Override
    protected void initMore(RedisConnectionFactory connectionFactory) {
        log.info("Sa-Token 登录态存储启用 Redis 5 兼容模式：setStringAndKeepTTL 改用 PTTL + SET XX，不再下发 KEEPTTL");
    }

    /**
     * 用「先读剩余 TTL、再带 XX 写回」两步复刻 {@code SET key value KEEPTTL XX}。
     *
     * <p>两处刻意与最朴素的写法不同：
     * <ul>
     *   <li>用 {@code PTTL} 而不是 {@code TTL}：后者以秒为单位向下取整，会话每次 update
     *       都会损失最多 1 秒，且损失是单向累积的 —— 长期活跃的登录态会慢慢缩短寿命。</li>
     *   <li>写回时保留 {@code XX}（{@link SetOption#ifPresent()}）：这是父类语义的另一半，
     *       「键已经不存在就不得复活它」。两步之间有个竞态窗口，若会话恰好在读完 TTL 之后过期，
     *       没有 XX 就会把一条本该消失的登录态重新存回去，用户表现为「已退出又被拉回在线」。</li>
     * </ul>
     *
     * <p>传入的 {@code key} 已由父类 {@code update} 做过 {@code wrapKey}，因此这里原样使用，
     * 与父类 {@code setStringAndKeepTTL} 的处理保持一致（它同样不再二次包装）。
     */
    @Override
    public void setStringAndKeepTTL(String key, String value) {
        stringRedisTemplate.execute((RedisCallback<Boolean>) connection -> {
            RedisSerializer<String> serializer = stringRedisTemplate.getStringSerializer();
            byte[] rawKey = serializer.serialize(key);
            byte[] rawValue = serializer.serialize(value);

            Long pttl = connection.keyCommands().pTtl(rawKey);
            if (pttl == null) {
                return Boolean.FALSE;
            }
            long remaining = pttl;
            if (remaining == ABSENT) {
                // 键不存在，对应 XX 语义：什么也不做
                return Boolean.FALSE;
            }
            if (remaining == PERSISTENT) {
                // 原本就没有过期时间，SET 不带 TTL 参数即可保持这一状态
                return connection.stringCommands().set(rawKey, rawValue, Expiration.persistent(), SetOption.ifPresent());
            }
            if (remaining <= 0) {
                // 剩余寿命不足 1 毫秒。写回去也会立刻消失，而 PX 0 会被服务端判为非法过期时间，
                // 所以直接放弃这次更新 —— 结果与让它自然过期一致。
                return Boolean.FALSE;
            }
            return connection.stringCommands().set(rawKey, rawValue, Expiration.milliseconds(remaining), SetOption.ifPresent());
        });
    }
}
