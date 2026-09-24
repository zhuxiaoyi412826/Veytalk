package com.im.remote.service;

import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import com.im.remote.config.RemoteProperties;
import com.im.remote.entity.RemoteSession;
import com.im.remote.manager.AgentRegistry;
import com.im.remote.protocol.RemoteEnvelope;
import com.im.remote.protocol.RemoteFrame;
import com.im.remote.protocol.RemoteProtocol;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 中继核心：会话绑定、双向转帧、只读策略与流量计数。
 *
 * <h2>中继的边界在哪里</h2>
 *
 * <p>服务端只路由不解密：二进制帧的载荷是 AES-GCM 密文，密钥在会话建立时
 * 分别经两条已鉴权通道下发（Agent 走 WS 文本帧、控制端走 session-start 帧），
 * 服务端只在内存里短暂持有用于下发，不存库、不出现在任何转发路径之外。
 * 文本帧只解析信封的 type/sid 做路由与策略，data 原样透传。
 *
 * <h2>为什么只读模式在服务端拦截而不是 Agent 端</h2>
 *
 * <p>Agent 端当然也会拒绝，但「被控方选择了只读」是授权决策，
 * 授权决策必须在掌握会话真相的一方生效——否则一个改过客户端的控制端
 * 能把只读会话的全部输入帧直接灌给 Agent，审计里只读形同虚设。
 * 服务端拦下后仍转发一条 error 回执，让控制端明确知道是策略而非故障。
 *
 * <p>本类不直接操作数据库（会话状态流转由 {@link RemoteSessionService} 负责），
 * 依赖用 {@link ObjectProvider} 持有：两个 Service 互相调用但不构成构造循环，
 * 延迟获取让初始化顺序与 Spring 的循环引用检测都保持宽松。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteRelayService {

    @Getter
    public static class ControlInfo {
        private final WebSocketSession session;
        private final Long userId;
        private final long sessionId;
        private final String ticket;
        @Setter
        private volatile long lastActiveMs;
        private final AtomicLong seq = new AtomicLong();

        ControlInfo(WebSocketSession session, Long userId, long sessionId, String ticket) {
            this.session = session;
            this.userId = userId;
            this.sessionId = sessionId;
            this.ticket = ticket;
            this.lastActiveMs = System.currentTimeMillis();
        }

        public long nextSeq() {
            return seq.incrementAndGet();
        }

        void touch() {
            this.lastActiveMs = System.currentTimeMillis();
        }
    }

    /** 一个活跃会话的中继绑定：两端连接 + 会话真相 + 流量计数 */
    public static class Binding {
        public final AgentRegistry.AgentInfo agent;
        public final ControlInfo control;
        public final String permission;
        public final byte[] aesKey;
        public final long createdAtMs;
        public volatile long lastActivityMs;
        public final AtomicLong bytes = new AtomicLong();

        Binding(AgentRegistry.AgentInfo agent, ControlInfo control, String permission, byte[] aesKey) {
            this.agent = agent;
            this.control = control;
            this.permission = permission;
            this.aesKey = aesKey;
            this.createdAtMs = System.currentTimeMillis();
            this.lastActivityMs = this.createdAtMs;
        }

        void touch() {
            this.lastActivityMs = System.currentTimeMillis();
        }
    }

    private final AgentRegistry agentRegistry;
    private final RemoteProperties properties;
    private final JsonUtil jsonUtil;
    private final ObjectProvider<RemoteSessionService> sessionServiceProvider;
    private final Map<Long, Binding> bindings = new ConcurrentHashMap<>();

    public AgentRegistry agentRegistry() {
        return agentRegistry;
    }

    /** 连接级缓冲区：容器默认 8KB 进不了屏幕块，握手后立刻按模块配置抬高 */
    public void configureBuffers(WebSocketSession session) {
        try {
            session.setBinaryMessageSizeLimit(properties.getMaxFrameBytes() + 8 * 1024);
            session.setTextMessageSizeLimit(properties.getMaxTextBytes());
        } catch (Exception e) {
            // 个别容器实现不允许连接级覆盖，降级为帧大小限制兜底即可，不应阻断建连
            log.warn("WS 缓冲区调整失败，沿用容器默认: {}", e.getMessage());
        }
    }

    /** 服务端 → Agent：注册成功帧，携运行参数 */
    public void sendReady(AgentRegistry.AgentInfo agent) {
        sendEnvelope(agent, RemoteEnvelope.of(RemoteProtocol.TYPE_READY, Map.of(
                "heartbeatSeconds", properties.getHeartbeatIntervalSeconds(),
                "maxFrameBytes", properties.getMaxFrameBytes(),
                "aes", properties.isAes())));
    }

    public void sendErrorAgent(AgentRegistry.AgentInfo agent, String code, String message) {
        sendEnvelope(agent, RemoteEnvelope.of(RemoteProtocol.TYPE_ERROR,
                Map.of("code", code, "message", message == null ? "" : message)));
    }

    /** 公开给 handler 的信封解析（失败返回 null，不抛异常打断容器回调） */
    public RemoteEnvelope parseEnvelope(String json) {
        return parse(json);
    }

    public ControlInfo registerControl(WebSocketSession raw, Long userId, long sessionId, String ticket) {
        ConcurrentWebSocketSessionDecorator session = new ConcurrentWebSocketSessionDecorator(
                raw, 10_000, 4 * 1024 * 1024);
        return new ControlInfo(session, userId, sessionId, ticket);
    }

    /**
     * 控制端 control-ready（ticket 已在入口消费）：双向绑定、下发会话参数。
     *
     * @param session 刚消费票据得到的会话（status=active，携带 aesKey 与 device 路由键）
     */
    public void onControlReady(ControlInfo control, RemoteSession session) {
        AgentRegistry.AgentInfo agent = agentRegistry.getByKey(session.getDevice());
        if (agent == null || !agent.getSession().isOpen()) {
            sendErrorControl(control, control.getSessionId(), "AGENT_OFFLINE", "被控端已离线");
            closeQuietly(control.session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        Binding binding = new Binding(agent, control, session.getPermission(), session.getAesKey());
        bindings.put(session.getId(), binding);
        // 给两端的会话参数帧：aesKey 走各自的已鉴权通道，一次一发不落日志
        String aesKeyB64 = binding.aesKey == null ? "" : java.util.Base64.getEncoder().encodeToString(binding.aesKey);
        sendEnvelope(agent, RemoteEnvelope.of(RemoteProtocol.TYPE_SESSION_START, session.getId(),
                Map.of("sessionId", String.valueOf(session.getId()),
                        "permission", session.getPermission(),
                        "aesKey", aesKeyB64)));
        sendEnvelopeControl(control, RemoteEnvelope.of(RemoteProtocol.TYPE_SESSION_START, session.getId(),
                Map.of("sessionId", String.valueOf(session.getId()),
                        "permission", session.getPermission(),
                        "aesKey", aesKeyB64)));
        sessionServiceProvider.getObject().onControlBound(session.getId());
        log.info("远程中继已建立: sessionId={}, inviter={}, deviceId={}",
                session.getId(), control.getUserId(), agent.getDeviceId());
    }

    public Binding binding(long sessionId) {
        return bindings.get(sessionId);
    }

    /** Agent 文本帧入口 */
    public void onAgentText(AgentRegistry.AgentInfo agent, String json) {
        agent.touch();
        RemoteEnvelope env = parse(json);
        if (env == null || env.getType() == null) {
            return;
        }
        Binding binding = agent.getDeviceId() == null ? null : findByAgent(agent);
        switch (env.getType()) {
            case RemoteProtocol.TYPE_PING -> sendEnvelope(agent, RemoteEnvelope.of(RemoteProtocol.TYPE_PONG,
                    Map.of("ts", System.currentTimeMillis())));
            case RemoteProtocol.TYPE_AUDIT -> {
                if (binding != null) {
                    sessionServiceProvider.getObject().recordFrameAudit(binding.control.getSessionId(), env);
                }
            }
            case RemoteProtocol.TYPE_SESSION_END -> {
                if (binding != null) {
                    closeBinding(binding, "invitee-end");
                }
            }
            default -> relayToControl(agent, binding, json);
        }
    }

    /** 控制端文本帧入口 */
    public void onControlText(ControlInfo control, String json) {
        control.touch();
        RemoteEnvelope env = parse(json);
        if (env == null || env.getType() == null) {
            return;
        }
        Binding binding = bindings.get(control.getSessionId());
        switch (env.getType()) {
            case RemoteProtocol.TYPE_PING -> sendEnvelopeControl(control, RemoteEnvelope.of(RemoteProtocol.TYPE_PONG,
                    Map.of("ts", System.currentTimeMillis())));
            case RemoteProtocol.TYPE_CONTROL_READY -> sessionServiceProvider.getObject()
                    .consumeTicketAndBind(control);
            case RemoteProtocol.TYPE_SESSION_END -> {
                if (binding != null) {
                    closeBinding(binding, "inviter-end");
                }
            }
            default -> relayToAgent(control, binding, json, env);
        }
    }

    /** 二进制帧入口：两端共用，按头部会话 ID 路由到对端 */
    public void onBinary(boolean fromAgent, WebSocketSession self, byte[] raw) {
        if (raw.length > properties.getMaxFrameBytes()) {
            closeQuietly(self, CloseStatus.POLICY_VIOLATION.withReason("frame too large"));
            return;
        }
        RemoteFrame frame;
        try {
            frame = RemoteFrame.decode(raw, 4096);
        } catch (IllegalArgumentException e) {
            log.warn("畸形二进制帧，断开: {}", e.getMessage());
            closeQuietly(self, CloseStatus.POLICY_VIOLATION.withReason("bad frame"));
            return;
        }
        Binding binding = bindings.get(frame.sessionId());
        if (binding == null) {
            // 会话已结束/未绑定的迟到帧：静默丢弃，两端各自有超时收尾
            return;
        }
        WebSocketSession peer = fromAgent ? binding.control.getSession() : binding.agent.getSession();
        sendBinary(peer, raw);
        binding.bytes.addAndGet(raw.length);
        binding.touch();
    }

    /** Agent 连接关闭：解除其所有绑定（会话以 agent-offline 结束） */
    public void onAgentClosed(AgentRegistry.AgentInfo agent) {
        Binding binding = findByAgent(agent);
        if (binding != null) {
            closeBinding(binding, "agent-offline");
        }
    }

    /** 控制端连接关闭：会话以 control-offline 结束 */
    public void onControlClosed(ControlInfo control) {
        Binding binding = bindings.get(control.getSessionId());
        if (binding != null && binding.control == control) {
            closeBinding(binding, "control-offline");
        }
    }

    /** 空闲巡检：当前绑定快照（拷贝列表，遍历中允许新绑定进出） */
    public java.util.List<Binding> bindingsSnapshot() {
        return java.util.List.copyOf(bindings.values());
    }

    public void closeIdleBinding(Binding binding, String reason) {
        closeBinding(binding, reason);
    }

    private Binding findByAgent(AgentRegistry.AgentInfo agent) {
        for (Binding binding : bindings.values()) {
            if (binding.agent == agent) {
                return binding;
            }
        }
        return null;
    }

    private void relayToControl(AgentRegistry.AgentInfo agent, Binding binding, String json) {
        if (binding == null) {
            return;
        }
        sendText(binding.control.getSession(), json);
        binding.bytes.addAndGet(json.getBytes(StandardCharsets.UTF_8).length);
        binding.touch();
    }

    private void relayToAgent(ControlInfo control, Binding binding, String json, RemoteEnvelope env) {
        if (binding == null) {
            return;
        }
        // 只读策略：输入帧不下发，回 error 让控制端明确感知是授权限制而非链路故障
        if (RemoteSession.PERMISSION_READONLY.equals(binding.permission)
                && (RemoteProtocol.TYPE_MOUSE.equals(env.getType()) || RemoteProtocol.TYPE_KEY.equals(env.getType()))) {
            sessionServiceProvider.getObject().recordInputBlocked(control.getSessionId(), env.getType());
            sendErrorControl(control, control.getSessionId(), "READONLY", ResultCode.REMOTE_READONLY.getMessage());
            return;
        }
        sendText(binding.agent.getSession(), json);
        binding.bytes.addAndGet(json.getBytes(StandardCharsets.UTF_8).length);
        binding.touch();
    }

    private void closeBinding(Binding binding, String reason) {
        Binding removed = bindings.remove(binding.control.getSessionId());
        if (removed == null) {
            return;
        }
        sessionServiceProvider.getObject().finishSession(binding.control.getSessionId(),
                reason, binding.bytes.get());
        sendEnvelope(binding.agent, RemoteEnvelope.of(RemoteProtocol.TYPE_SESSION_CLOSED,
                binding.control.getSessionId(), Map.of("reason", reason)));
        sendEnvelopeControl(binding.control, RemoteEnvelope.of(RemoteProtocol.TYPE_SESSION_CLOSED,
                binding.control.getSessionId(), Map.of("reason", reason)));
        closeQuietly(binding.control.getSession(), CloseStatus.NORMAL);
        log.info("远程中继已结束: sessionId={}, reason={}, bytes={}",
                binding.control.getSessionId(), reason, binding.bytes.get());
    }

    public long boundBytes(long sessionId) {
        Binding binding = bindings.get(sessionId);
        return binding == null ? 0 : binding.bytes.get();
    }

    /* ==================== 收发工具 ==================== */

    private RemoteEnvelope parse(String json) {
        try {
            return jsonUtil.fromJson(json, RemoteEnvelope.class);
        } catch (BusinessException e) {
            log.warn("无法解析的文本帧: {}", e.getMessage());
            return null;
        }
    }

    private void sendEnvelope(AgentRegistry.AgentInfo agent, RemoteEnvelope env) {
        env.setSeq(agent.nextSeq());
        sendText(agent.getSession(), jsonUtil.toJson(env));
    }

    private void sendEnvelopeControl(ControlInfo control, RemoteEnvelope env) {
        env.setSeq(control.nextSeq());
        sendText(control.getSession(), jsonUtil.toJson(env));
    }

    private void sendErrorControl(ControlInfo control, long sessionId, String code, String message) {
        sendEnvelopeControl(control, RemoteEnvelope.of(RemoteProtocol.TYPE_ERROR, sessionId,
                Map.of("code", code, "message", message)));
    }

    private void sendText(WebSocketSession session, String text) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(text));
        } catch (Exception e) {
            // 发送失败通常意味着对端已断，关闭流程由容器回调接管，这里只留线索
            log.debug("中继文本帧发送失败: sessionId={}, err={}", session.getId(), e.getMessage());
        }
    }

    private void sendBinary(WebSocketSession session, byte[] raw) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(raw));
        } catch (Exception e) {
            log.debug("中继二进制帧发送失败: sessionId={}, err={}", session.getId(), e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 关闭失败无副作用
        }
    }
}
