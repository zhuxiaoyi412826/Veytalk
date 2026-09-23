package com.im.common.security.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解：在 {@code seconds} 秒的固定窗口内，同一维度最多放行 {@code count} 次。
 *
 * <p>由 {@link RateLimitInterceptor} 在请求进入 Controller 之前检查，
 * 超限抛 {@code TOO_MANY_REQUESTS} 业务异常，前端按普通业务错误提示「操作过于频繁」。
 * 方法级注解优先于类级注解；两者都没有的接口只受
 * {@code im.rate-limit.global-count/global-seconds} 的全局按 IP 兜底限制。
 *
 * <p>维度选择的原则：匿名可达的接口（登录、注册、验证码）只能按 IP 限——
 * 此时还没有可信的用户身份；登录后的业务接口按用户限，
 * 否则同一出口 NAT 下的一整个办公室会共享一个配额，一个人脚本刷接口全办公室被锁。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 窗口内允许的最大请求次数 */
    int count();

    /** 窗口长度（秒），默认 60 */
    int seconds() default 60;

    /** 限流维度，默认按客户端 IP */
    Dimension dimension() default Dimension.IP;

    /**
     * 计数器名称，同一名称共享一个计数窗口；
     * 留空时按「类名#方法名」自动生成。方法重载或多个端点想共用配额时显式指定。
     */
    String key() default "";

    enum Dimension {
        /** 按客户端 IP（兼容反向代理头） */
        IP,
        /** 按登录用户；未登录时退化为按 IP */
        USER
    }
}
