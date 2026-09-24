package com.im.remote.agent;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 本机识别码只读接口：在 127.0.0.1 上开一个 HTTP 端点，供本机浏览器打开的
 * 控制端页面读取「本机识别码」与在线状态。
 *
 * <p>网页与 Agent 是两个独立进程，没有这条回环通道页面就拿不到识别码
 * （识别码由 Agent 生成持有，尤其免账号模式下它不归属任何账号、服务端
 * 也不会把它列给任何用户）。只绑 loopback，外网不可达；返回的识别码本来
 * 就是要告诉控制方的信息，故不再加鉴权。
 *
 * <p>端口被占（本机已跑另一个 Agent 实例）时静默跳过，不影响主流程。
 */
public final class LocalInfoServer {

    private final HttpServer server;

    private LocalInfoServer(HttpServer server) {
        this.server = server;
    }

    /** 启动失败（端口占用等）返回 null，调用方忽略即可 */
    public static LocalInfoServer start(AgentConfig config, BooleanSupplier online) {
        int port = config.localInfoPort();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/local-info", exchange -> {
                // 浏览器跨源读取需要 CORS 头；页面可能是 http/https/file 任意源
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("deviceId", config.deviceId());
                body.put("deviceName", config.deviceName());
                body.put("accessCode", config.accessCode());
                body.put("online", online.getAsBoolean());
                byte[] bytes = MiniJson.write(body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                exchange.close();
            });
            server.start();
            System.out.println("本机识别码接口已启动: http://127.0.0.1:" + port + "/local-info");
            return new LocalInfoServer(server);
        } catch (Exception e) {
            System.err.println("本机识别码接口未启动（端口 " + port + " 被占？）: " + e.getMessage());
            return null;
        }
    }

    public void stop() {
        server.stop(0);
    }
}
