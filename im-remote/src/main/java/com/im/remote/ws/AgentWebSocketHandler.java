package com.im.remote.ws;

import com.im.remote.entity.RemoteDevice;
import com.im.remote.manager.AgentRegistry;
import com.im.remote.protocol.RemoteEnvelope;
import com.im.remote.protocol.RemoteProtocol;
import com.im.remote.service.RemoteDeviceService;
import com.im.remote.service.RemoteRelayService;
import com.im.remote.service.RemoteSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 被控端 Agent 的连接处理器。
 *
 * <p>连接生命周期：握手（token 鉴权）→ auth 帧（登记设备、绑定 deviceId）→
 * 心跳/邀请/中继。auth 之前的连接不参与任何会话，超时未 auth 由巡检任务回收。
 *
 * <p>accept / reject 帧在这里分流给会话状态机（它们改变库里的会话真相），
 * 其余业务帧一律交中继原样转发——处理器不做任何业务判断，
 * 业务判断（权限、开关、路径越界）发生在 Agent 本地，这是被控端授权模型的前提。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentRegistry agentRegistry;
    private final RemoteRelayService relayService;
    private final RemoteSessionService sessionService;
    private final RemoteDeviceService deviceService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        relayService.configureBuffers(session);
        Long userId = (Long) session.getAttributes().get(AgentHandshakeInterceptor.ATTR_USER_ID);
        // 识别码模式（无 token 握手）的连接按匿名处理：userId=0，设备不归属任何账号
        AgentRegistry.AgentInfo info = agentRegistry.register(session, userId == null ? 0L : userId);
        // 运行参数握手后即下发：Agent 据此安排心跳节奏与单帧上限，两端配置不再各说各话
        relayService.sendReady(info);
        log.info("Agent 连接建立（待认证）: userId={}, wsId={}", info.getUserId(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AgentRegistry.AgentInfo agent = agentRegistry.bySessionId(session.getId());
        if (agent == null) {
            closeQuietly(session);
            return;
        }
        RemoteEnvelope env = relayService.parseEnvelope(message.getPayload());
        if (env == null || env.getType() == null) {
            return;
        }
        switch (env.getType()) {
            case RemoteProtocol.TYPE_AUTH -> handleAuth(agent, env);
            case RemoteProtocol.TYPE_ACCEPT -> handleAccept(agent, env, session);
            case RemoteProtocol.TYPE_REJECT -> handleReject(agent, env, session);
            default -> relayService.onAgentText(agent, message.getPayload());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AgentRegistry.AgentInfo agent = agentRegistry.bySessionId(session.getId());
        if (agent == null) {
            closeQuietly(session);
            return;
        }
        byte[] raw = new byte[message.getPayloadLength()];
        message.getPayload().duplicate().get(raw);
        relayService.onBinary(true, session, raw);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AgentRegistry.AgentInfo agent = agentRegistry.bySessionId(session.getId());
        if (agent == null) {
            return;
        }
        relayService.onAgentClosed(agent);
        agentRegistry.remove(session);
        if (agent.isAuthed() && agent.getDeviceId() != null) {
            RemoteDevice device = deviceService.find(agent.getUserId(), agent.getDeviceId());
            if (device != null) {
                deviceService.updateStatus(device.getId(), RemoteDevice.STATUS_OFFLINE);
            }
            log.info("Agent 连接断开: userId={}, deviceId={}, status={}",
                    agent.getUserId(), agent.getDeviceId(), status.getCode());
        }
    }

    private void handleAuth(AgentRegistry.AgentInfo agent, RemoteEnvelope env) {
        Map<String, Object> data = env.getData();
        String deviceId = data == null ? null : str(data.get("deviceId"));
        String deviceName = data == null ? null : str(data.get("deviceName"));
        String os = data == null ? null : str(data.get("os"));
        String accessCode = normalizeCode(data == null ? null : str(data.get("accessCode")));
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 64) {
            relayService.sendErrorAgent(agent, "AUTH", "deviceId 非法");
            closeQuietly(agent.getSession());
            return;
        }
        // 匿名连接必须凭合法识别码立足；账号连接带了 token，码只是可选的附加接入入口
        if (agent.getUserId() == 0L && accessCode == null) {
            relayService.sendErrorAgent(agent, "AUTH", "未登录账号时必须提供识别码（6-12 位字母数字）");
            closeQuietly(agent.getSession());
            return;
        }
        if (accessCode != null) {
            AgentRegistry.AgentInfo holder = agentRegistry.getByCode(accessCode);
            if (holder != null && holder != agent) {
                // 码冲突：拒绝后来者，保护已在线设备不被顶替后收到发错人的邀请
                relayService.sendErrorAgent(agent, "AUTH", "识别码已被其他在线设备占用，请更换");
                closeQuietly(agent.getSession());
                return;
            }
        }
        agentRegistry.confirm(agent, deviceId, deviceName, os, accessCode);
        deviceService.upsert(agent.getUserId(), deviceId, deviceName, os, accessCode, RemoteDevice.STATUS_IDLE);
        log.info("Agent 已认证: userId={}, deviceId={}, deviceName={}, accessCode={}",
                agent.getUserId(), deviceId, deviceName, accessCode == null ? "-" : accessCode);
    }

    /** 识别码归一化：去空白、转大写、校验字符集；非法返回 null */
    private String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toUpperCase();
        return code.matches("[A-Z0-9]{6,12}") ? code : null;
    }

    private void handleAccept(AgentRegistry.AgentInfo agent, RemoteEnvelope env, WebSocketSession session) {
        try {
            sessionService.handleAccept(agent, env);
        } catch (Exception e) {
            relayService.sendErrorAgent(agent, "ACCEPT", e.getMessage());
        }
    }

    private void handleReject(AgentRegistry.AgentInfo agent, RemoteEnvelope env, WebSocketSession session) {
        try {
            sessionService.handleReject(agent, env);
        } catch (Exception e) {
            relayService.sendErrorAgent(agent, "REJECT", e.getMessage());
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
            // 关闭失败无副作用
        }
    }
}
