package com.im.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 跨域配置。
 *
 * <p>前后端分离部署，前端开发服务器（默认 {@code http://localhost:5173}）直连 8080，
 * 必须放开 CORS。用 {@link CorsFilter} 而不是 {@code addCorsMappings}，
 * 是为了让预检请求在进入 Sa-Token 拦截器之前就被处理掉。
 *
 * <p>{@code allowedOriginPatterns} 而非 {@code allowedOrigins}：
 * 携带凭证时 Spring 不允许 origin 为 {@code *}，用 patterns 才能既支持通配又允许凭证。
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class CorsConfig {

    private final ImProperties properties;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = properties.getCors().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            config.addAllowedOriginPattern("*");
        } else {
            origins.forEach(config::addAllowedOriginPattern);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        // 暴露追踪头，前端才能在响应里读到并展示，便于报障
        config.setExposedHeaders(List.of("X-Trace-Id", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
