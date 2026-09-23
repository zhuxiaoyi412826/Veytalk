package com.im.common.security.ratelimit;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 限流拦截器：注解配额 + 全局按 IP 兜底，两层都在请求进入 Controller 之前完成。
 *
 * <p>注册顺序刻意排在 Sa-Token 拦截器之前（{@code order=-100}）：
 * 暴力破解的每个请求都要先被限流挡下，而不是先让 Sa-Token 跑一遍
 * JWT 验签 + Redis 会话查询再拒绝——被刷的时候，最贵的操作应该发生在最便宜的防线之后。
 *
 * <p>超限抛 {@link BusinessException}，由 {@code GlobalExceptionHandler} 翻译成标准
 * JSON 错误体（code=1010），与项目里所有业务错误一个形状，前端无需为限流写特殊分支。
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ImProperties properties;
    private final RateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ImProperties.RateLimiting config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return true;
        }
        String ip = SecurityUtil.getClientIp();

        // 第一层：端点注解配额。只对 Controller 方法生效，静态资源等 handler 直接跳过
        if (handler instanceof HandlerMethod handlerMethod) {
            RateLimit limit = handlerMethod.getMethodAnnotation(RateLimit.class);
            if (limit == null) {
                limit = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RateLimit.class);
            }
            if (limit != null) {
                String id = ip;
                if (limit.dimension() == RateLimit.Dimension.USER) {
                    Long userId = SecurityUtil.getUserIdOrNull();
                    // 未登录时退化为按 IP：注解标错位置（标到了匿名接口）也不至于把所有人算成同一个用户
                    id = userId != null ? "u" + userId : ip;
                }
                String name = TextUtil.isNotBlank(limit.key())
                        ? limit.key()
                        : handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
                acquire(name, id, limit.count(), limit.seconds());
            }
        }

        // 第二层：全局按 IP 兜底，覆盖所有没标注解的接口（含匿名白名单与文件下载）
        acquire("global", ip, config.getGlobalCount(), config.getGlobalSeconds());
        return true;
    }

    private void acquire(String name, String id, int count, int seconds) {
        if (!rateLimiter.tryAcquire(name, id, count, seconds)) {
            log.warn("触发限流: endpoint={}, id={}, limit={}次/{}秒", name, id, count, seconds);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS);
        }
    }
}
