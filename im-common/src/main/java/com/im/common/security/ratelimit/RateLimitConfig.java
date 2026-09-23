package com.im.common.security.ratelimit;

import com.im.common.config.ImProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 限流拦截器注册。
 *
 * <p>与 {@code SaTokenConfigure} 分开而不是塞进同一个 Configurer：
 * 两者的关注点（限流 vs 鉴权）和开关（im.rate-limit.enabled vs sa-token）都独立，
 * 混在一起后想临时关掉限流排障就得连鉴权配置一起动。
 *
 * <p>{@code order(-100)} 保证限流先于 Sa-Token 拦截器（默认 order=0）执行，
 * 被刷的请求在最便宜的一层就被拒掉。
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    private final ImProperties properties;
    private final RateLimiter rateLimiter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(properties, rateLimiter))
                .addPathPatterns("/api/**")
                .order(-100);
    }
}
