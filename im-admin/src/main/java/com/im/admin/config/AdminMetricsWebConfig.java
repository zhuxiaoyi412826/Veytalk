package com.im.admin.config;

import com.im.admin.monitor.SlowRequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 后台模块自己的 MVC 装配（三期）。
 *
 * <p>刻意不改动 im-common 的 {@code SaTokenConfigure}：鉴权拦截器与性能观测拦截器各管各的，
 * 后者挂了不该把登录校验一起带下水；两个 WebMvcConfigurer 会由 Spring 自动合并登记。
 *
 * <p>拦截器只登记在 {@code /api/**}：actuator 端点在独立的 management 端口上
 * （见 application.yml 的 management 段），不经过主端口的分发链路，这里不需要写 exclude。
 * 把「监控页所在的 /api/admin/**」也量进去是有意的——后台自己翻日志翻出慢接口，同样应该被看见。
 */
@Configuration
@RequiredArgsConstructor
public class AdminMetricsWebConfig {

    private final SlowRequestInterceptor slowRequestInterceptor;

    @Bean
    public WebMvcConfigurer slowRequestMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(slowRequestInterceptor).addPathPatterns("/api/**");
            }
        };
    }
}
