package com.im.websocket.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * WebSocket 握手 Origin 归一化：把桌面端 {@code file://} 页面发出的「不可解析 Origin」
 * 替换成请求自身的 scheme://host，让 Spring 框架层的 Origin 校验能放行。
 *
 * <h2>为什么需要这个 shim</h2>
 *
 * <p>Spring 的 {@code OriginHandshakeInterceptor} 跑在项目自己的握手拦截器<strong>之前</strong>，
 * 且 {@code setAllowedOriginPatterns("*")} 并不放行所有字面值：它要把 Origin 当 URI 解析后
 * 再按模式比对，而 Electron 桌面端页面从 {@code file://} 加载，Chromium 握手时带的 Origin 是
 * 字面量 {@code null}（或 {@code file://}），解析失败即被静默 403——框架只记 debug 日志，
 * 项目的拦截器根本不会执行，服务端因此「一片沉默」：既无连接建立也无握手拒绝的 WARN。
 * 客户端表现为 {@code new WebSocket()} 后约百毫秒内 onerror + close 1006，
 * 而同一渲染进程的普通 HTTP 请求完全正常（HTTP 不走这道校验），极难归因。
 *
 * <h2>为什么归一化不扩大攻击面</h2>
 *
 * <p>与 {@code WebSocketConfig} 里 {@code setAllowedOriginPatterns("*")} 的论证同源：
 * 本项目不存在 Cookie 登录态（{@code sa-token.is-read-cookie=false}），握手凭证是
 * URL 上的一次性短票，而短票必须先经带 {@code satoken} 请求头的接口换取，跨站页面拿不到。
 * Origin 校验在这里只是框架默认行为，并非本系统的鉴权边界；把「无法解析的 Origin」
 * 归一成请求自身来源，等价于承认「Origin 头不可信、鉴权只认票据」这一既有设计。
 *
 * <p>只挂在 {@code /ws} 与 {@code /ws/*} 上：普通 REST 请求的 Origin 语义（CORS）不受影响。
 */
public class WsOriginNormalizeFilter implements Filter {

    /** Chromium 对 opaque / file:// 来源页面序列化出的 Origin 字面量 */
    private static final String OPAQUE_ORIGIN = "null";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            String origin = httpRequest.getHeader("Origin");
            if (origin != null && isUnparsable(origin)) {
                chain.doFilter(new OriginOverrideWrapper(httpRequest, selfOrigin(httpRequest)), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** {@code null} 与 {@code file:} 开头的 Origin 都无法被 Spring 当 URI 解析 */
    private boolean isUnparsable(String origin) {
        return OPAQUE_ORIGIN.equals(origin) || origin.startsWith("file:");
    }

    /** 用请求自身的 scheme://host 作为等价 Origin：与「同源握手」语义一致 */
    private String selfOrigin(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return request.getScheme() + "://" + host;
    }

    /** 只改写 Origin 一个头，其余全部透传 */
    private static final class OriginOverrideWrapper extends HttpServletRequestWrapper {

        private final String origin;

        private OriginOverrideWrapper(HttpServletRequest request, String origin) {
            super(request);
            this.origin = origin;
        }

        @Override
        public String getHeader(String name) {
            return "origin".equalsIgnoreCase(name) ? origin : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return "origin".equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(origin))
                    : super.getHeaders(name);
        }
    }
}
