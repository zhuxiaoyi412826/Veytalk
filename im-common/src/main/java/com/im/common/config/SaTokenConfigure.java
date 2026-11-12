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
     *
     * <p>{@code /api/auth/password/**} 是找回密码，用户正是因为登录不进来才走这条路，
     * 天然匿名可达。它的安全性不靠登录态，而靠三道门叠起来：图形验证码闸门
     * （一次性消费）+ 6 位场景验证码 + 按 IP 的 {@code @RateLimit}，缺一不可。
     *
     * <p>协议文本读的两条（{@code list} 与 {@code view/**}）匿名可达是<b>合规要求</b>而不是便利：
     * 官网页脚那条《隐私政策》必须在没登录时就能打开——「收集我的信息之前先告诉我你收集了什么」
     * 不能反过来要求我先注册。同前缀下的 {@code consent} 与 {@code pending} <b>不</b>在白名单里：
     * 那两条要写谁的表态，必须有登录态，所以只把读的两条逐个路径写出来而不是放 {@code /api/agreement/**} 整片。
     */
    private static final List<String> EXCLUDE_PATTERNS = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/login/sms",
            "/api/auth/login/email",
            // 网页端扫码登录：出码与轮询状态时网页尚无登录态，天然匿名可达（授权 / 已扫码仍由 @SaCheckLogin 守）
            "/api/auth/qr-login/create",
            "/api/auth/qr-login/status",
            "/api/auth/password/**",
            "/api/captcha/**",
            "/api/file/download/**",
            // 群机器人 Webhook：外部系统无登录态回调，凭 URL 里的 token 认证（能力 URL 模式），
            // 安全性靠 token 不可猜 + 按 IP 的 @RateLimit + 机器人启用状态三重门控
            "/api/robot/webhook/**",
            // 法定文本公示：只读的两条，逐路径列出（详见类注释），写的那两条留在拦截器里面
            "/api/agreement/list",
            "/api/agreement/view/**"
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
