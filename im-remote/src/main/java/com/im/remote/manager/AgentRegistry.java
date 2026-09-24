package com.im.remote.manager;

import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线 Agent 注册表：{@code userId:deviceId} → 连接信息。
 *
 * <p>存的是 {@link ConcurrentWebSocketSessionDecorator} 包装后的会话：
 * 中继转发发生在「控制端连接的消息线程」上，而邀请/会话通知发生在 REST 线程或
 * 定时任务线程上，同一个 Agent 连接会被多线程写。裸 {@code WebSocketSession}
 * 并发写会直接把连接写坏（Tomcat 报 TEXT_PARTIAL_WRITING 并断连），必须包一层。
 *
 * <p>auth 之前的连接也在表里（authed=false）：未认证的连接同样要受心跳/空闲
 * 巡检的约束，否则一个只握手不发 auth 的客户端能永久占住一条连接。
 *
 * <p>作为独立的 {@code @Component} 注册：它被 handler、relay、session service 共享同一实例，
 * 且自身无任何依赖。切勿改回由 {@code RemoteWebSocketConfig} 的 {@code @Bean} 方法产出——
 * 那条路径会形成 {@code Config → Handler → Registry(本类的 @Bean 工厂) → Config} 的构造循环，
 * 编译期不报错，只在容器启动时以「circular references」失败。
 */
@Component
public class AgentRegistry {

    /** 连接信息：身份 + 包装后的可并发写会话 + 活跃时间 */
    @Getter
    public static class AgentInfo {
        private final WebSocketSession session;
        private final Long userId;
        /** auth 帧到达前为 null */
        private volatile String deviceId;
        private volatile String deviceName;
        private volatile String os;
        /** 识别码（auth 帧上报，可为 null）：支持控制方跨账号凭码接入（ToDesk 模式） */
        private volatile String accessCode;
        /** auth 帧到达后才为 true；true 之前不允许参与任何会话 */
        private volatile boolean authed;
        /** 最近一次收到该 Agent 任意帧的时间（毫秒），心跳巡检依据 */
        private volatile long lastActiveMs;
        /** 服务端发起帧（邀请/会话通知）的发送序号 */
        private final AtomicLong seq = new AtomicLong();

        AgentInfo(WebSocketSession session, Long userId) {
            this.session = session;
            this.userId = userId;
            this.lastActiveMs = System.currentTimeMillis();
        }

        public long nextSeq() {
            return seq.incrementAndGet();
        }

        public void touch() {
            this.lastActiveMs = System.currentTimeMillis();
        }

        void markAuthed(String deviceId, String deviceName, String os, String accessCode) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.os = os;
            this.accessCode = accessCode;
            this.authed = true;
        }
    }

    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();

    public static String key(Long userId, String deviceId) {
        return userId + ":" + deviceId;
    }

    /**
     * Agent 连接建立时登记。同一 key 的旧连接直接关掉——同一台机器重复启动 Agent
     * （改了配置又起一个进程）不应出现两个「在线」实例互相抢会话。
     */
    public AgentInfo register(WebSocketSession raw, Long userId) {
        ConcurrentWebSocketSessionDecorator session = new ConcurrentWebSocketSessionDecorator(
                raw, 10_000, 4 * 1024 * 1024);
        AgentInfo info = new AgentInfo(session, userId);
        String pendingKey = key(userId, "pending-" + raw.getId());
        agents.put(pendingKey, info);
        return info;
    }

    /** auth 帧到达后用真实 deviceId 迁移索引键 */
    public void confirm(AgentInfo info, String deviceId, String deviceName, String os, String accessCode) {
        info.markAuthed(deviceId, deviceName, os, accessCode);
        agents.remove(key(info.getUserId(), "pending-" + info.getSession().getId()));
        String realKey = key(info.getUserId(), deviceId);
        AgentInfo previous = agents.put(realKey, info);
        if (previous != null && previous != info) {
            closeQuietly(previous);
        }
    }

    /** 连接关闭时摘除；仅当表中确实存着这条连接才删（防止误删同 key 的新连接） */
    public void remove(WebSocketSession session) {
        agents.values().removeIf(info -> info.getSession().getId().equals(session.getId()));
    }

    public AgentInfo get(Long userId, String deviceId) {
        AgentInfo info = agents.get(key(userId, deviceId));
        return info != null && info.isAuthed() && info.getSession().isOpen() ? info : null;
    }

    /** 按 {@link #key} 格式的连接标识取在线 Agent */
    public AgentInfo getByKey(String key) {
        if (key == null) {
            return null;
        }
        AgentInfo info = agents.get(key);
        return info != null && info.isAuthed() && info.getSession().isOpen() ? info : null;
    }

    /** 按识别码取在线 Agent（大小写不敏感）；码冲突时取第一个在线持有者 */
    public AgentInfo getByCode(String accessCode) {
        if (accessCode == null || accessCode.isBlank()) {
            return null;
        }
        for (AgentInfo info : agents.values()) {
            if (info.isAuthed() && accessCode.equalsIgnoreCase(info.getAccessCode())
                    && info.getSession().isOpen()) {
                return info;
            }
        }
        return null;
    }

    /**
     * 向 Agent 发送已序列化的文本帧（包装后的会话可并发写，异常只留线索不打断调用方）。
     */
    public void send(AgentInfo agent, String json) {
        WebSocketSession session = agent.getSession();
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            // 发送失败通常意味着对端已断，关闭流程由容器回调接管
        }
    }

    /** 按会话 ID 反查（handler 回调里只有 WebSocketSession） */
    public AgentInfo bySessionId(String sessionId) {
        for (AgentInfo info : agents.values()) {
            if (info.getSession().getId().equals(sessionId)) {
                return info;
            }
        }
        return null;
    }

    public List<AgentInfo> listAuthedByUser(Long userId) {
        List<AgentInfo> result = new ArrayList<>();
        for (AgentInfo info : agents.values()) {
            if (info.isAuthed() && userId.equals(info.getUserId())) {
                result.add(info);
            }
        }
        return result;
    }

    /** 巡检用快照：拷贝列表，避免遍历中被并发修改 */
    public List<AgentInfo> snapshot() {
        return new ArrayList<>(agents.values());
    }

    private void closeQuietly(AgentInfo info) {
        try {
            info.getSession().close();
        } catch (Exception ignored) {
            // 关闭失败无副作用，注册表已经把它挤出去了
        }
    }
}
