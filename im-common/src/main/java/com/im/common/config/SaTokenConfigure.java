package com.im.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Sa-Token 全局拦截配置。
 *
 * <p>策略：拦截器覆盖 {@code /api/**} 全部路径，白名单只在 {@link SaRouter} 层面放行匿名接口。
 *
 * <p>这里刻意不使用 {@code InterceptorRegistration.excludePathPatterns}：一旦某个路径被 Spring MVC
 * 排除，拦截器根本不会执行，方法上的 {@code @SaCheckLogin} / {@code @SaCheckPermission} 也就跟着失效了。
 * 而 {@code /api/auth} 下的 {@code logout}、{@code refresh}、{@code me} 恰恰需要登录，
 * 必须让拦截器照常进入、由注解来决定是否放行。
 *
 * <p>文档、静态资源与错误页不在 {@code /api/**} 之内，天然不会被拦截。
 */
@Configuration(proxyBeanMethods = false)
public class SaTokenConfigure implements WebMvcConfigurer {

    /** 拦截器覆盖的路径 */
    private static final String[] INCLUDE_PATTERNS = {"/api/**"};

    /**
     * 匿名可访问的路径；其余 /api/** 一律要求登录。
     *
     * <p>{@code /api/file/download/**} 在这里并不意味着文件可以匿名下载：
     * 浏览器渲染 {@code <img src>} 时根本不会带上 {@code satoken} 请求头，
     * 因此该端点改用 URL 上的短时票据认证，鉴权全部在 {@code FileController} 内部完成
     * （票据无效且无登录态时一样返回 401）。把它排除在拦截器外只是为了让票据有机会被读到。
     */
    private static final List<String> EXCLUDE_PATTERNS = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/login/sms",
            "/api/captcha/**",
            "/api/file/download/**"
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        SaInterceptor interceptor = new SaInterceptor(handler ->
                SaRouter.match(INCLUDE_PATTERNS)
                        .notMatch(EXCLUDE_PATTERNS)
                        .check(r -> StpUtil.checkLogin()));
        // 开启注解鉴权，@SaCheckLogin / @SaCheckPermission / @SaCheckRole 才会生效
        interceptor.isAnnotation(true);
        registry.addInterceptor(interceptor).addPathPatterns(INCLUDE_PATTERNS);
    }
}
