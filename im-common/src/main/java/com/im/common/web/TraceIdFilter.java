package com.im.common.web;

import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器。
 *
 * <p>优先透传上游传入的 {@code X-Trace-Id}，没有则本地生成一个 32 位无横线 UUID，
 * 写入 MDC 供日志 pattern 输出，并回写响应头方便前端排障时定位服务端日志。
 *
 * <p>注册为最高优先级，保证后续所有过滤器、拦截器与业务日志都带上 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(ImConstants.HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        } else {
            // 上游可能传入超长或非法字符，做一次收敛避免污染日志
            traceId = traceId.length() > 64 ? traceId.substring(0, 64) : traceId;
        }
        MDC.put(Result.TRACE_ID, traceId);
        response.setHeader(ImConstants.HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(Result.TRACE_ID);
            MDC.remove(ImConstants.MDC_USER_ID);
        }
    }
}
