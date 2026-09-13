package com.im.websocket.manager;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条已建立的 WebSocket 连接。
 *
 * <p>这里持有的 {@code session} 必须是被 {@code ConcurrentWebSocketSessionDecorator}
 * 包装过的那一个，而不是容器直接交出来的原始会话：{@link WebSocketSession#sendMessage} 并不是线程安全的，
 * 而推送天然发生在多个线程上——业务线程推新消息、定时任务推心跳超时、另一个用户的请求线程推已读回执，
 * 三条路径可能同时命中同一条连接。裸会话在并发写下会把两个帧的字节交织在一起，
 * 客户端收到的是无法解析的半截 JSON，而且这种损坏是间歇性的，几乎无法复现。
 *
 * <p>{@code lastActiveTime} 用 {@link AtomicLong} 而不是 volatile long：
 * 它同时被容器 I/O 线程（收到帧时 touch）和定时任务线程（扫描超时）访问，
 * 而「读出来判断超时」与「写回当前时间」必须是各自原子的，否则扫描线程可能拿着一个陈旧值去关闭一条刚刚活跃过的连接。
 */
public final class WsConnection {

    /**
     * 连接在 {@link WebSocketSession#getAttributes()} 里的存放键。
     *
     * <p>身份信息只存这一份，不再另建「sessionId → 连接」的第二个索引：
     * 两份索引就必须保证同步，而关闭回调可能在注册完成之前到达，
     * 那时两个索引会各自看到不一致的世界。存进 attributes 由会话自己携带，天然只有一个真相来源。
     */
    public static final String ATTRIBUTE_KEY = "im.ws.connection";

    private final WebSocketSession session;
    private final WsPrincipal principal;
    private final long connectedAt;
    private final AtomicLong lastActiveTime;

    public WsConnection(WebSocketSession session, WsPrincipal principal) {
        this.session = session;
        this.principal = principal;
        this.connectedAt = System.currentTimeMillis();
        this.lastActiveTime = new AtomicLong(this.connectedAt);
    }

    /**
     * 从会话属性里取回连接，取不到返回 {@code null}。
     *
     * <p>装饰器会把 {@code getAttributes()} 委派给被包装的原始会话，
     * 所以无论拿到的是装饰后的还是会原始的，读到的都是同一个 map。
     */
    public static WsConnection from(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        Object attribute = session.getAttributes().get(ATTRIBUTE_KEY);
        return attribute instanceof WsConnection connection ? connection : null;
    }

    public WebSocketSession session() {
        return session;
    }

    public WsPrincipal principal() {
        return principal;
    }

    public Long userId() {
        return principal.userId();
    }

    public String deviceId() {
        return principal.deviceId();
    }

    /** 容器分配的会话 ID，仅用于日志与注册表去重，不携带任何身份信息 */
    public String sessionId() {
        return session.getId();
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public long connectedAt() {
        return connectedAt;
    }

    public long lastActiveTime() {
        return lastActiveTime.get();
    }

    /** 距上次收到客户端帧的毫秒数 */
    public long idleMillis() {
        return System.currentTimeMillis() - lastActiveTime.get();
    }

    /**
     * 刷新活跃时间。收到任何上行帧都调用，不只是 {@code ping}——
     * 客户端在正常发消息就说明连接是活的，没理由因为「它忘了单独发心跳」而把它踢掉。
     */
    public void touch() {
        lastActiveTime.set(System.currentTimeMillis());
    }

    /**
     * 发送一条已经序列化好的文本报文。
     *
     * <p>入参是字符串而不是 {@code WsPacket}：JSON 序列化在 {@link WsSessionManager} 里对每个报文只做一次，
     * 群发给 200 个成员时不必把同一个对象序列化 200 遍。
     *
     * <p>这里只负责发，不负责失败后的清理——摘除坏连接是注册表的职责，
     * 因为只有它知道这条连接挂在哪个用户的哪个设备位上。
     *
     * @throws IOException 底层写入失败，调用方据此判定连接已不可用
     */
    public void send(String payload) throws IOException {
        session.sendMessage(new TextMessage(payload));
    }
}
