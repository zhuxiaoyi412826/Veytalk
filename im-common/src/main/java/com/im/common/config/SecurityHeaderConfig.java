package com.im.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器：给所有后端响应统一补上防点击劫持/防 MIME 嗅探/防 Referer 泄露的头。
 *
 * <p>XSS 的第一道防线在前端（Vue 插值天然转义，两处 v-html 也先转义再高亮），
 * 这里是纵深防御的最后一层：即便某个响应被浏览器当成 HTML 渲染，
 * {@code nosniff} 也会阻止它按猜测的类型执行；{@code DENY} 则让整个站点无法被 iframe 套壳。
 *
 * <p>{@code Cache-Control: no-store} 只加在 JSON 接口上：聊天消息、token、用户资料
 * 不该留在任何中间缓存里；文件下载（/api/file/download）刻意排除——
 * 它靠 ETag/If-None-Match 协商缓存省流量，no-store 会把 304 机制整个废掉。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityHeaderConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> securityHeaderFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");
                response.setHeader("Referrer-Policy", "no-referrer");
                String uri = request.getRequestURI();
                if (uri != null && uri.startsWith("/api/") && !uri.startsWith("/api/file/download")) {
                    response.setHeader("Cache-Control", "no-store");
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        // 排在 CORS 过滤器之后：预检请求（OPTIONS）需要 CORS 头先落好，安全头补不补无所谓
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("securityHeaderFilter");
        return registration;
    }
}
