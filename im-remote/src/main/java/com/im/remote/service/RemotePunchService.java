package com.im.remote.service;

import com.im.remote.config.RemoteProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 打洞地址反射服务：告诉两端「你在公网这边看起来是哪个 ip:port」。
 *
 * <h2>为什么必须有它</h2>
 *
 * <p>NAT 后的机器无法自知对外映射地址：Agent 的内网地址是 192.168.x.x，
 * 而控制端要往「Agent 的公网映射端口」发包才能把洞打通，这个映射只有
 * 网关知道，最省事的问法就是给公网上的服务端发一个 UDP 包、看它回的头里
 * 写了什么源地址。协议与 STUN 同构，但这里只有一个用途，
 * 因此用 JDK 原生 {@link DatagramSocket} 手搓 9 字节问答，
 * 不引入 ICE/STUN 库，也不给 im-remote 加任何依赖。
 *
 * <h2>安全边界</h2>
 *
 * <p>这个端口无状态、无鉴权，本质是一台「会回显来源地址的 UDP 反射器」，
 * 也就是教科书意义上的 UDP 放大/反射跳板。三点约束把它按到最小面：
 * 响应严格小于请求（不回任何多余字节、不含任何服务端信息）、
 * 每来源 60 秒窗口限 {@code punchRateLimit} 次、只在 {@code direct.enabled}
 * 且配了 {@code punchHost} 时才监听。真上线仍应只在需要的机器上放行该端口。
 *
 * <p>与 HTTP 服务完全独立：不占 8080，也不用 Netty（im-remote 没这个依赖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemotePunchService {

    /** 协议魔数，兼作「是不是本服务的包」的快速判据 */
    private static final byte[] MAGIC = "IMP1".getBytes(StandardCharsets.US_ASCII);
    /** 请求：magic(4) + role(1) + nonce(4) */
    private static final int REQUEST_LEN = 9;
    /** 响应：magic(4) + nonce(4) + ipLen(1) + ip(4|16) + port(2) */
    private static final int RESPONSE_MAX_LEN = 27;

    private final RemoteProperties properties;

    private volatile DatagramSocket socket;
    private volatile Thread worker;
    private final AtomicLong served = new AtomicLong();
    /** 来源地址 -> [窗口起始毫秒, 窗口内计数]；量级极小（每会话两端各问几次），不做淘汰器 */
    private final Map<String, long[]> rateWindow = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        RemoteProperties.Direct direct = properties.getDirect();
        if (!properties.isEnabled() || !direct.isEnabled() || !direct.isUdpEnabled()) {
            log.info("远程直连打洞反射服务未启用（direct.enabled={} udpEnabled={})",
                    direct.isEnabled(), direct.isUdpEnabled());
            return;
        }
        if (direct.getPunchHost() == null || direct.getPunchHost().isBlank()) {
            log.warn("远程直连打洞反射服务未启动：im.remote.direct.punch-host 为空，UDP 直连将自动禁用");
            return;
        }
        try {
            DatagramSocket created = new DatagramSocket(direct.getPunchPort());
            created.setReceiveBufferSize(64 * 1024);
            socket = created;
            worker = new Thread(this::serveLoop, "remote-punch-reflector");
            worker.setDaemon(true);
            worker.start();
            log.info("远程直连打洞反射服务已启动: udp://0.0.0.0:{}（对外地址 {}）",
                    direct.getPunchPort(), direct.getPunchHost());
        } catch (Exception e) {
            // 端口被占或权限不足：只影响 UDP 直连这一档，局域网直连与中继都不受影响
            log.warn("远程直连打洞反射服务启动失败（UDP 直连将不可用）: port={}, err={}",
                    direct.getPunchPort(), e.getMessage());
        }
    }

    private void serveLoop() {
        byte[] buffer = new byte[RESPONSE_MAX_LEN];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (socket != null && !socket.isClosed()) {
            try {
                socket.receive(packet);
                respond(packet);
            } catch (Exception e) {
                if (socket != null && !socket.isClosed()) {
                    log.debug("打洞反射服务处理包失败: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void respond(DatagramPacket packet) throws Exception {
        byte[] data = packet.getData();
        int len = packet.getLength();
        if (len < REQUEST_LEN || data[0] != MAGIC[0] || data[1] != MAGIC[1]
                || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            return;
        }
        if (!allow(packet.getAddress())) {
            log.debug("打洞反射限流: {}", packet.getAddress().getHostAddress());
            return;
        }
        // nonce 原样回带，两端据此确认这是自己对反射器的提问的应答
        ByteBuffer body = ByteBuffer.allocate(RESPONSE_MAX_LEN);
        body.put(MAGIC);
        body.put(data, 5, 4);
        byte[] address = packet.getAddress().getAddress();
        body.put((byte) address.length);
        body.put(address);
        body.putShort((short) packet.getPort());
        socket.send(new DatagramPacket(body.array(), 0, body.position(),
                packet.getSocketAddress()));
        long total = served.incrementAndGet();
        if (total % 200 == 1) {
            log.info("远程直连打洞反射服务已应答 {} 次", total);
        }
    }

    /** 60 秒滑动窗口限次：地址粒度（IPv4 地址 / IPv6 取前缀足够，不引 CIDR 计算） */
    private boolean allow(InetAddress address) {
        int limit = properties.getDirect().getPunchRateLimit();
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long[] window = rateWindow.computeIfAbsent(address.getHostAddress(), k -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] > 60_000) {
                window[0] = now;
                window[1] = 0;
            }
            window[1]++;
            if (window[1] > limit) {
                return false;
            }
        }
        if (rateWindow.size() > 4096) {
            rateWindow.entrySet().removeIf(e -> now - e.getValue()[0] > 120_000);
        }
        return true;
    }

    /** 供审计与前端展示：反射服务是否真的在跑（配了 host 但端口被占时为 false） */
    public boolean isRunning() {
        return socket != null && !socket.isClosed();
    }

    @PreDestroy
    public void stop() {
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
