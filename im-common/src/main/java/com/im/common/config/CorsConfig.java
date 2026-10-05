package com.im.common.config;

import com.im.common.constant.ImConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
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
 *
 * <h2>为什么 /ws 路径跳过本过滤器</h2>
 *
 * <p>WebSocket 握手按规范不受 CORS 约束（浏览器不会对 WS 发起预检），
 * 它的 Origin 策略由 Spring WebSocket 自己的 {@code setAllowedOriginPatterns} 负责。
 * 而 Electron 桌面端从 {@code file://} 加载页面，握手带的 Origin 是字面量 {@code null}，
 * 既不在本过滤器的白名单里、也不是合法 URI，放在 {@code /**} 下会被这里先于
 * 握手逻辑静默 403（桌面端全部 WS 连不上的真因之一）。故 /ws 前缀直接放行，
 * 交由 {@code WsOriginNormalizeFilter} + 握手层处理。
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

        // WS 握手不走 CORS（见类注释）：/ws 前缀直接透传，避免桌面端的 null Origin 被白名单误杀
        CorsFilter filter = new CorsFilter(source) {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();
                String ws = ImConstants.WS_ENDPOINT;
                if (path.equals(ws) || path.startsWith(ws + "/")) {
                    filterChain.doFilter(request, response);
                    return;
                }
                super.doFilterInternal(request, response, filterChain);
            }
        };
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
