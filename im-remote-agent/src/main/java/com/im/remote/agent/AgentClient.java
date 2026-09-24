package com.im.remote.agent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 被控端核心：登录 → 长连接 → 注册设备 → 心跳/重连 → 帧分发。
 *
 * <p>连接模型与服务端协议一一对应（文本帧 JSON 信封 + 定长头二进制帧），
 * 断线按指数退避重连（1s 起、上限 30s）——被控端要 7x24 挂在目标机器上，
 * 服务器重启、网络抖动都必须能自动恢复，不需要人去点。
 *
 * <p>AES 密钥只在收到 session-start 帧时构建、会话结束即销毁，
 * Agent 内存中不存在长期有效的会话密钥。
 */
public class AgentClient {

    /** 协议常量镜像（被控端零依赖，不引 im-remote 的类） */
    public static final byte FRAME_SCREEN = 1;
    public static final byte FRAME_FILE = 2;

    /** UI 桥接：AgentClient 不直接触碰 Swing，便于无 UI 场景测试 */
    public interface AgentUi {
        void log(String message);

        /** EDT 上弹窗询问授权 */
        AlertUI.Invite askInvite(String nickname, String requestedPermission);

        void onSessionStarted(String nickname, String permission);

        void onSessionEnded(String reason);
    }

    private static final Path LOCAL_LOG = Path.of(System.getProperty("user.dir"), "agent-local.log");

    private final AgentConfig config;
    private final AgentUi ui;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong seq = new AtomicLong();

    private final ScreenCapturer capturer = new ScreenCapturer(this);
    private final FileOps fileOps;
    private final SystemOps systemOps;
    private final InputHandler inputHandler;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "agent-scheduler");
        t.setDaemon(true);
        return t;
    });
    /** 慢操作单线程队列：exec/下载/递归删除不占 WS 回调线程，也天然串行化文件传输 */
    private final java.util.concurrent.ExecutorService opExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "agent-ops");
                t.setDaemon(true);
                return t;
            });

    private volatile WebSocket ws;
    private volatile String token;
    private volatile boolean connectedOnce;
    /* 收到 ready 帧到断开之间为 true，供本机识别码接口上报在线状态 */
    private volatile boolean authed;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopRequested;

    /* 服务端下发的运行参数（ready 帧） */
    private volatile int heartbeatSeconds = 30;
    private volatile long maxFrameBytes = 1024 * 1024;
    private volatile boolean aesEnabled = true;

    /* 会话状态：0 表示无会话 */
    private volatile long sid;
    private volatile String permission = "readonly";
    private volatile AesCipher cipher;
    private volatile String inviterNickname = "";
    private ScheduledFuture<?> heartbeatTask;

    public AgentClient(AgentConfig config, AgentUi ui) {
        this.config = config;
        this.ui = ui;
        this.fileOps = new FileOps(this, config);
        this.systemOps = new SystemOps(this, config);
        this.inputHandler = new InputHandler(this, capturer);
    }

    /* ==================== 连接主循环 ==================== */

    /** 阻塞直到 stop()；在独立线程上运行 */
    public void runLoop() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        stopRequested = false;
        long backoffMs = 1000;
        while (!stopRequested) {
            try {
                token = login();
                connectAndServe();
                backoffMs = 1000; // 正常断开（含被服务端踢）都重置退避
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("连接失败: " + e.getMessage() + "，" + backoffMs / 1000 + " 秒后重试");
            }
            if (stopRequested) {
                break;
            }
            try {
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running.set(false);
        cleanupSession("agent-stop");
        log("Agent 已停止");
    }

    public void stop() {
        stopRequested = true;
        WebSocket current = ws;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "agent-stop");
        }
        scheduler.shutdownNow();
        opExecutor.shutdownNow();
    }

    private String login() throws Exception {
        if (config.username().isEmpty() || config.password().isEmpty()) {
            // 识别码模式：不登录账号，返回 null 走匿名握手，身份由 auth 帧的 accessCode 确立
            log("未填账号密码，以识别码模式连接（识别码 " + config.accessCode() + "）");
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loginType", "username");
        body.put("account", config.username());
        body.put("password", config.password());
        body.put("deviceId", "agent");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.serverUrl() + "/api/auth/login"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("登录 HTTP " + response.statusCode());
        }
        Map<String, Object> result = MiniJson.parseObject(response.body());
        if (MiniJson.integer(result, "code", -1) != 200 || !(result.get("data") instanceof Map)) {
            throw new IllegalStateException("登录失败: " + MiniJson.str(result, "message"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String value = MiniJson.str(data, "token");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("登录响应缺少 token");
        }
        return value;
    }

    private void connectAndServe() throws Exception {
        String url = config.wsBase() + "/ws/remote/agent";
        if (token != null) {
            url += "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        }
        WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new Listener()).join();
        ws = socket;
        log("已连接服务器，等待注册参数…");
        // java.net.http 没有 closeFuture，用门闩把「阻塞直到断开」接在回调上
        disconnectLatch = new java.util.concurrent.CountDownLatch(1);
        disconnectLatch.await();
        disconnectLatch = null;
    }

    private volatile java.util.concurrent.CountDownLatch disconnectLatch;

    /* ==================== WS 回调 ==================== */

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();
        private java.io.ByteArrayOutputStream binaryBuffer = new java.io.ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            connectedOnce = true;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String json = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleText(json);
                } catch (Exception e) {
                    log("文本帧处理异常: " + e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // 回调给的 buffer 可能是底层大数组的 slice，只能按 remaining 读
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.write(chunk, 0, chunk.length);
            if (last) {
                byte[] raw = binaryBuffer.toByteArray();
                binaryBuffer = new java.io.ByteArrayOutputStream();
                try {
                    handleBinary(raw);
                } catch (Exception e) {
                    log("二进制帧处理异常: " + e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log("连接关闭: code=" + statusCode + " reason=" + reason);
            onDisconnected();
            releaseLatch();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log("连接异常: " + error.getMessage());
            onDisconnected();
            releaseLatch();
        }

        private void releaseLatch() {
            java.util.concurrent.CountDownLatch latch = disconnectLatch;
            if (latch != null) {
                latch.countDown();
            }
            ws = null;
        }
    }

    private void onDisconnected() {
        authed = false;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        capturer.stop();
        cleanupSession("connection-lost");
    }

    /* ==================== 文本帧分发 ==================== */

    @SuppressWarnings("unchecked")
    private void handleText(String json) {
        Map<String, Object> env = MiniJson.parseObject(json);
        String type = MiniJson.str(env, "type");
        Long envSid = env.get("sid") instanceof Number n ? n.longValue() : null;
        long reqSeq = MiniJson.lng(env, "seq", 0);
        Map<String, Object> data = env.get("data") instanceof Map ? (Map<String, Object>) env.get("data") : Map.of();
        switch (type == null ? "" : type) {
            case "ready" -> onReady(data);
            case "pong" -> { /* 心跳应答，无需处理 */ }
            case "invite" -> onInvite(data);
            case "session-start" -> onSessionStart(data);
            case "session-closed" -> {
                String reason = MiniJson.str(data, "reason");
                cleanupSession(reason == null ? "closed" : reason);
            }
            case "error" -> log("服务端错误: " + MiniJson.str(data, "code") + " " + MiniJson.str(data, "message"));
            case "screen-start" -> {
                capturer.start(sid, MiniJson.integer(data, "fps", 10),
                        MiniJson.integer(data, "quality", 75), (int) MiniJson.lng(data, "monitor", -1));
            }
            case "screen-stop" -> capturer.stop();
            case "mouse" -> {
                try {
                    inputHandler.handleMouse(data);
                } catch (Exception e) {
                    sendFrameError(reqSeq, "INPUT", e.getMessage());
                }
            }
            case "key" -> {
                try {
                    inputHandler.handleKey(data);
                } catch (Exception e) {
                    sendFrameError(reqSeq, "INPUT", e.getMessage());
                }
            }
            case "monitor-switch" -> {
                capturer.configure(0, 0, (int) MiniJson.lng(data, "index", 0));
                sendResult(reqSeq, Map.of("monitor", MiniJson.lng(data, "index", 0)));
            }
            case "list-dir" -> sendResult(reqSeq, fileOpsSafeList(MiniJson.str(data, "path")));
            case "file-get" -> {
                String transferId = MiniJson.str(data, "transferId");
                String path = MiniJson.str(data, "path");
                opExecutor.submit(() -> {
                    try {
                        fileOps.download(transferId, path);
                        sendResult(reqSeq, Map.of("transferId", transferId, "done", true));
                    } catch (Exception e) {
                        sendFrameError(reqSeq, "FILE", e.getMessage());
                    }
                });
            }
            case "file-put" -> {
                try {
                    fileOps.beginUpload(MiniJson.str(data, "transferId"), MiniJson.str(data, "dir"),
                            MiniJson.str(data, "name"), MiniJson.lng(data, "size", 0));
                    sendResult(reqSeq, Map.of("transferId", MiniJson.str(data, "transferId"), "ready", true));
                } catch (Exception e) {
                    sendFrameError(reqSeq, "FILE", e.getMessage());
                }
            }
            case "rm", "rename", "mkdir" -> handleFileChange(type, reqSeq, data);
            case "ps-list" -> sendResult(reqSeq, systemOps.processList());
            case "ps-kill" -> opExecutor.submit(() -> handleSystem(reqSeq, () ->
                    systemOps.kill(MiniJson.lng(data, "pid", 0))));
            case "ps-run" -> opExecutor.submit(() -> handleSystem(reqSeq, () ->
                    systemOps.run(MiniJson.str(data, "cmd"))));
            case "exec" -> opExecutor.submit(() -> handleSystem(reqSeq, () ->
                    systemOps.exec(MiniJson.str(data, "cmd"))));
            case "power" -> handleSystem(reqSeq, () -> systemOps.power(MiniJson.str(data, "action")));
            case "clip-sync" -> {
                String text = MiniJson.str(data, "text");
                if (text != null) {
                    systemOps.syncClipboard(text);
                    sendResult(reqSeq, Map.of("synced", true));
                }
            }
            default -> log("未知帧类型: " + type);
        }
        if (envSid != null && sid == 0) {
            // 无会话却收到会话帧：不中断连接，只留线索（中继绑定竞态下可能瞬时出现）
            log("无活跃会话但收到帧: type=" + type + ", sid=" + envSid);
        }
    }

    private Map<String, Object> fileOpsSafeList(String path) {
        try {
            return fileOps.listDir(path);
        } catch (Exception e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    private void handleFileChange(String type, long reqSeq, Map<String, Object> data) {
        opExecutor.submit(() -> handleSystem(reqSeq, () -> switch (type) {
            case "rm" -> fileOps.remove(MiniJson.str(data, "path"));
            case "rename" -> fileOps.rename(MiniJson.str(data, "from"), MiniJson.str(data, "to"));
            default -> fileOps.makeDir(MiniJson.str(data, "path"));
        }));
    }

    private void handleSystem(long reqSeq, java.util.concurrent.Callable<Map<String, Object>> action) {
        try {
            sendResult(reqSeq, action.call());
        } catch (Exception e) {
            sendFrameError(reqSeq, "LOCAL", e.getMessage());
        }
    }

    /* ==================== 会话生命周期 ==================== */

    private void onReady(Map<String, Object> data) {
        heartbeatSeconds = MiniJson.integer(data, "heartbeatSeconds", 30);
        maxFrameBytes = MiniJson.lng(data, "maxFrameBytes", 1024 * 1024);
        aesEnabled = MiniJson.bool(data, "aes", true);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("deviceId", config.deviceId());
        auth.put("deviceName", config.deviceName());
        auth.put("os", System.getProperty("os.name", "unknown") + " / JDK " + System.getProperty("java.version"));
        auth.put("accessCode", config.accessCode());
        sendEnvelope("auth", null, auth);
        startHeartbeat();
        authed = true;
        log("设备已注册: " + config.deviceName() + "（识别码 " + config.accessCode() + "）");
    }

    /** 是否已注册到中继（ready 帧后为 true，断开即 false） */
    public boolean isOnline() {
        return authed;
    }

    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                sendEnvelope("ping", null, Map.of("ts", System.currentTimeMillis()));
            } catch (Exception e) {
                log("心跳发送失败: " + e.getMessage());
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    private void onInvite(Map<String, Object> data) {
        String sessionId = MiniJson.str(data, "sessionId");
        String nickname = MiniJson.str(data, "inviterNickname");
        String requested = MiniJson.str(data, "permission");
        if (sessionId == null) {
            return;
        }
        if (config.refuse() || sid != 0) {
            // 已有会话时新邀请直接拒：一台被控机同一时刻只服务一个控制端
            sendEnvelope("reject", Long.parseLong(sessionId), Map.of("sessionId", sessionId));
            log("邀请已自动拒绝: " + nickname + (config.refuse() ? " (拒绝接入开关)" : " (已有会话)"));
            return;
        }
        log("收到远程邀请: " + nickname);
        SwingSupport.onEdtResult(() -> {
            AlertUI.Invite invite = ui.askInvite(nickname, requested);
            if (invite.accept()) {
                Map<String, Object> accept = new LinkedHashMap<>();
                accept.put("sessionId", sessionId);
                accept.put("permission", invite.permission());
                sendEnvelope("accept", Long.parseLong(sessionId), accept);
                this.inviterNickname = nickname;
                log("已授权: " + nickname + " (" + invite.permission() + ")");
            } else {
                sendEnvelope("reject", Long.parseLong(sessionId), Map.of("sessionId", sessionId));
                log("用户拒绝了邀请");
            }
            return null;
        });
    }

    private void onSessionStart(Map<String, Object> data) {
        sid = MiniJson.lng(data, "sessionId", 0);
        permission = MiniJson.str(data, "permission") == null ? "readonly" : MiniJson.str(data, "permission");
        String keyB64 = MiniJson.str(data, "aesKey");
        if (aesEnabled && keyB64 != null && !keyB64.isBlank()) {
            cipher = new AesCipher(java.util.Base64.getDecoder().decode(keyB64));
        } else {
            cipher = null;
        }
        if (inviterNickname.isEmpty()) {
            inviterNickname = "控制端";
        }
        SwingSupport.onEdt(() -> ui.onSessionStarted(inviterNickname, permission));
        log("会话开始: sid=" + sid + ", permission=" + permission + ", aes=" + (cipher != null));
        sendAudit("agent-ready", "permission=" + permission);
    }

    /** 被控端主动结束（悬浮条点击 / 本地拒绝后续帧） */
    public void endSessionByAgent() {
        long current = sid;
        if (current != 0) {
            sendEnvelope("session-end", current, Map.of("reason", "invitee-end"));
        }
        cleanupSession("invitee-end");
    }

    private void cleanupSession(String reason) {
        capturer.stop();
        fileOps.abortAll();
        if (sid == 0) {
            return;
        }
        sid = 0;
        cipher = null;
        permission = "readonly";
        SwingSupport.onEdt(() -> ui.onSessionEnded(reason));
        log("会话结束: " + reason);
    }

    /* ==================== 帧收发原语 ==================== */

    public void sendEnvelope(String type, Long envelopeSid, Map<String, Object> data) {
        WebSocket socket = ws;
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("v", 1);
        env.put("type", type);
        if (envelopeSid != null) {
            env.put("sid", envelopeSid);
        }
        env.put("seq", seq.incrementAndGet());
        env.put("ts", System.currentTimeMillis());
        if (data != null && !data.isEmpty()) {
            env.put("data", data);
        }
        socket.sendText(MiniJson.write(env), true);
    }

    /** 二进制帧：[1B 类型][8B sid][4B metaLen][meta][payload]，载荷按会话密钥加密 */
    public void sendBinaryFrame(byte frameType, long envelopeSid, String meta, byte[] plainPayload) {
        WebSocket socket = ws;
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        try {
            byte[] payload = cipher == null ? plainPayload : cipher.encrypt(plainPayload);
            byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(13 + metaBytes.length + payload.length);
            buffer.put(frameType);
            buffer.putLong(envelopeSid);
            buffer.putInt(metaBytes.length);
            buffer.put(metaBytes);
            buffer.put(payload);
            buffer.flip();
            socket.sendBinary(buffer, true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log("二进制帧发送失败: " + e.getMessage());
        }
    }

    private void handleBinary(byte[] raw) {
        if (raw.length < 13) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte frameType = buffer.get();
        long frameSid = buffer.getLong();
        int metaLen = buffer.getInt();
        if (frameType != FRAME_FILE || metaLen < 0 || 13 + metaLen > raw.length) {
            return;
        }
        if (frameSid != sid) {
            return;
        }
        byte[] metaBytes = new byte[metaLen];
        buffer.get(metaBytes);
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        fileOps.handleChunk(new String(metaBytes, StandardCharsets.UTF_8), payload);
    }

    public byte[] decryptPayload(byte[] payload) {
        return cipher == null ? payload : cipher.decrypt(payload);
    }

    public void sendResult(long reqSeq, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("reqSeq", reqSeq);
        sendEnvelope("result", sid, payload);
    }

    public void sendFrameError(long reqSeq, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reqSeq", reqSeq);
        payload.put("code", code);
        payload.put("message", message == null ? "" : message);
        sendEnvelope("error", sid, payload);
    }

    /** 无 reqSeq 上下文的本地策略拒绝（只读拦截），seq 对齐 0 由控制端按 type 展示 */
    public void sendLocalError(String code, String message) {
        sendFrameError(0, code, message);
        sendAudit("input-blocked", code);
    }

    public void sendAudit(String action, String detail) {
        sendEnvelope("audit", sid, Map.of("action", action, "detail", detail == null ? "" : detail));
    }

    /* ==================== 共享访问 ==================== */

    public long currentSid() {
        return sid;
    }

    public boolean allowInput() {
        return sid != 0 && "operate".equals(permission);
    }

    public long maxFrameBytes() {
        return maxFrameBytes;
    }

    public void log(String message) {
        ui.log(message);
        try {
            Files.writeString(LOCAL_LOG,
                    "[" + java.time.LocalDateTime.now() + "] " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 本地日志写失败不影响主流程
        }
    }
}
