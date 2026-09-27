package com.im.remote.agent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 打洞直连服务端（UDP）：接受控制端「穿过 NAT 打进来」的报文。
 *
 * <h2>为什么用同一个 socket 去问反射服务</h2>
 *
 * <p>NAT 后的机器不知道自己的公网映射地址，必须问服务端「我看起来是谁」。
 * 关键在于<b>问的 socket 必须就是收数据的 socket</b>：多数家用网关对同一源端口的
 * 出站流量复用同一映射端口，用 18925 这个口去问，回显的映射端口才是
 * 「别人能打进来的那个口」。另起一个随机端口去问，拿到的地址对收包毫无用处。
 *
 * <h2>链路状态只有「一个对端」</h2>
 *
 * <p>一台被控机同一时刻只服务一个控制端（与中继侧一致），所以这里只保留一条
 * {@link UdpPeer}。握手前收到的杂包（打洞期对端还没建立状态）一律静默丢，
 * 不回任何错误——回包等于告诉扫描者「这个端口活着」。
 *
 * <p>可靠通道全部交给 {@link DirectArq}：画面走不可靠通道（丢了等下一帧），
 * 指令、信封、文件块走 ARQ 通道。每 50ms 驱动一次重传定时器，每 3s 发一个
 * PING 维持 NAT 映射（映射超时普遍在 30~60s，不续的会话会在中途静默断掉）。
 */
public final class DirectUdpServer {

    private static final List<DirectUdpServer> INSTANCES = new CopyOnWriteArrayList<>();
    /** 收包缓冲：协商 MTU 上限 1200，留足余量即可，超大包直接判畸形 */
    private static final int MAX_DATAGRAM = 2048;
    private static final byte[] MAGIC = "IMP1".getBytes(StandardCharsets.US_ASCII);
    private static final long PING_INTERVAL_MS = 3000;
    private static final long TICK_MS = 50;

    private final DirectChannel channel;
    private final int port;
    private volatile DatagramSocket socket;
    private volatile Thread receiver;
    private volatile Thread ticker;
    private volatile UdpPeer peer;
    private volatile boolean running;

    DirectUdpServer(DirectChannel channel, int port) {
        this.channel = channel;
        this.port = port;
    }

    void start() throws Exception {
        DatagramSocket bound = new DatagramSocket();
        bound.bind(new InetSocketAddress(port));
        bound.setReceiveBufferSize(2 * 1024 * 1024);
        bound.setSendBufferSize(1024 * 1024);
        socket = bound;
        running = true;
        receiver = new Thread(this::receiveLoop, "direct-udp-rx-" + port);
        receiver.setDaemon(true);
        receiver.start();
        ticker = new Thread(this::tickLoop, "direct-udp-tx-" + port);
        ticker.setDaemon(true);
        ticker.start();
        INSTANCES.add(this);
    }

    /* ==================== 打洞预热 ==================== */

    /**
     * 向反射服务问自己的公网地址，拿到后异步把候选发给控制端。
     *
     * <p>这一步同时完成了打洞的前半程：从 18925 口发出的这个包会在网关上留下映射，
     * 控制端随后往这个映射回发才可能被放行。
     */
    void punch(String host, int punchPort) {
        Thread thread = new Thread(() -> {
            try {
                DatagramSocket current = socket;
                if (current == null || current.isClosed()) {
                    return;
                }
                int nonce = (int) System.nanoTime();
                ByteBuffer request = ByteBuffer.allocate(9);
                request.put(MAGIC).put((byte) 0).putInt(nonce);
                current.send(new DatagramPacket(request.array(), request.position(),
                        new InetSocketAddress(InetAddress.getByName(host), punchPort)));
                byte[] buffer = new byte[64];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                current.setSoTimeout(3000);
                try {
                    current.receive(reply);
                } finally {
                    current.setSoTimeout(0);
                }
                if (reply.getLength() < 13 || buffer[0] != MAGIC[0] || buffer[1] != MAGIC[1]
                        || buffer[2] != MAGIC[2] || buffer[3] != MAGIC[3]) {
                    channel.logPunch("反射服务应答格式非法，UDP 直连仅局域网可用");
                    return;
                }
                int ipLength = buffer[8] & 0xFF;
                if (ipLength != 4 && ipLength != 16) {
                    channel.logPunch("反射服务回显地址长度异常: " + ipLength);
                    return;
                }
                byte[] address = new byte[ipLength];
                System.arraycopy(buffer, 9, address, 0, ipLength);
                int mapped = ByteBuffer.wrap(buffer, 9 + ipLength, 2).getShort() & 0xFFFF;
                channel.setPublicAddress(InetAddress.getByAddress(address).getHostAddress(), mapped);
                channel.logPunch("公网映射 " + mapped + "（对端地址 " + InetAddress.getByAddress(address).getHostAddress() + "）");
            } catch (Exception e) {
                // 拿不到公网映射只影响跨网段这一档：局域网直连与中继都照常
                channel.logPunch("打洞预热失败: " + e.getMessage());
            }
        }, "direct-udp-punch");
        thread.setDaemon(true);
        thread.start();
    }

    /* ==================== 收包主循环 ==================== */

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running && socket != null && !socket.isClosed()) {
            try {
                socket.receive(packet);
                onDatagram(packet);
            } catch (Exception e) {
                if (running) {
                    // 收包失败多为 socket 被关（会话收尾），不刷日志
                    return;
                }
            }
        }
    }

    private void onDatagram(DatagramPacket packet) {
        int length = packet.getLength();
        Dxp.Packet parsed = Dxp.decode(packet.getData(), length);
        if (parsed == null) {
            return;
        }
        SocketAddress from = packet.getSocketAddress();
        if (parsed.type() == Dxp.TYPE_HELLO) {
            handleHello(parsed, from);
            return;
        }
        UdpPeer current = peer;
        if (current == null || !current.address.equals(from)) {
            return;
        }
        channel.accountRecv(length);
        current.receive(parsed, length);
    }

    private void handleHello(Dxp.Packet parsed, SocketAddress from) {
        UdpPeer current = peer;
        if (current != null && current.alive() && current.address.equals(from)) {
            // 控制端没收到 ACK 会周期性重发 HELLO，这里幂等重发，不重建链路（重建会丢 ARQ 序号）
            String replay = channel.replayAck(current.peer());
            if (replay != null) {
                sendAck(replay, from);
            }
            return;
        }
        DirectChannel.Hello result = channel.hello(Dxp.helloJson(parsed.body()), DirectChannel.PATH_UDP,
                fromAddressText(from));
        // ACK 必须和 TCP 档一样带 DXP 头：对端先按报文解包，裸 JSON 会被判成畸形包直接丢掉，
        // 表现是「HELLO 一直重发、握手永远不成」，而两侧日志都看不出问题
        sendAck(result.ackJson(), from);
        if (!result.accept()) {
            return;
        }
        UdpPeer created = new UdpPeer(from, Math.max(512, result.mtu()));
        peer = created;
        channel.attach(created);
    }

    private static String fromAddressText(SocketAddress address) {
        return address == null ? "?" : address.toString().replace("/", "");
    }

    private void sendAck(String json, SocketAddress address) {
        sendTo(Dxp.encode(Dxp.TYPE_HELLO_ACK, Dxp.CH_CONTROL, (byte) 0, 0, 0,
                json.getBytes(StandardCharsets.UTF_8)), address);
    }

    private void sendTo(byte[] payload, SocketAddress address) {
        DatagramSocket current = socket;
        if (current == null || current.isClosed()) {
            return;
        }
        try {
            current.send(new DatagramPacket(payload, payload.length, address));
        } catch (Exception ignored) {
            // 打洞期对端地址可能已经不可达，失败由 ARQ 与上层回落兜住
        }
    }

    private void tickLoop() {
        while (running) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            UdpPeer current = peer;
            if (current != null) {
                current.tick();
            }
        }
    }

    /* ==================== 单条 UDP 链路 ==================== */

    /** 一个已握手的对端：地址 + ARQ 状态 + 心跳 */
    private final class UdpPeer implements DirectChannel.Link {
        private final SocketAddress address;
        private final DirectArq arq;
        private final String peerText;
        private long lastPingMs;
        private volatile boolean closed;

        UdpPeer(SocketAddress address, int mtu) {
            this.address = address;
            this.peerText = fromAddressText(address);
            this.arq = new DirectArq(mtu, this::send, (kind, message) -> {
                if (!closed) {
                    channel.deliverMessage(kind, message);
                }
            });
        }

        private void send(byte[] datagram) {
            DatagramSocket current = socket;
            if (current == null || current.isClosed() || closed) {
                return;
            }
            try {
                current.send(new DatagramPacket(datagram, datagram.length, address));
                channel.accountSent(datagram.length);
            } catch (Exception e) {
                // 端口不可写（多为对端已关会话），下一跳上层会判死
                closed = true;
            }
        }

        void receive(Dxp.Packet packet, int wireBytes) {
            if (!arq.onPacket(packet) && !handleControl(packet)) {
                return;
            }
            if (!arq.healthy()) {
                // 连续多轮重传无进展：判定洞已失效，交给上层回落中继
                arq.markUnhealthy();
                channel.detach(this);
            }
        }

        /** 控制类报文（握手后的 PING/PONG/CLOSE）；返回 false 表示未知类型 */
        private boolean handleControl(Dxp.Packet packet) {
            switch (packet.type()) {
                case Dxp.TYPE_PING -> arq.sendControl(Dxp.TYPE_PONG, (byte[]) null);
                case Dxp.TYPE_PONG -> { /* 对端的心跳应答，无需处理 */ }
                case Dxp.TYPE_CLOSE -> {
                    channel.detach(this);
                    markClosed();
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        void tick() {
            if (closed) {
                return;
            }
            arq.tick();
            long now = System.currentTimeMillis();
            if (now - lastPingMs > PING_INTERVAL_MS) {
                lastPingMs = now;
                // 心跳同时兼作 NAT 映射续期：不发数据的链路会在 30~60s 后被网关静默回收
                arq.sendControl(Dxp.TYPE_PING, (byte[]) null);
            }
            if (!arq.healthy()) {
                channel.detach(this);
                markClosed();
            }
        }

        @Override
        public String path() {
            return DirectChannel.PATH_UDP;
        }

        @Override
        public String peer() {
            return peerText;
        }

        @Override
        public boolean alive() {
            return !closed && arq.healthy();
        }

        @Override
        public boolean sendScreen(byte kind, byte[] message) {
            return !closed && arq.sendScreen(kind, message);
        }

        @Override
        public boolean sendReliable(byte kind, byte[] message) {
            return !closed && arq.sendReliable(kind, message);
        }

        @Override
        public void close(String reason) {
            if (closed) {
                return;
            }
            arq.sendControl(Dxp.TYPE_CLOSE, reason == null ? "" : reason);
            markClosed();
        }

        /** 不发包的本地关停：对端已不可达/会话收尾时用 */
        private void markClosed() {
            closed = true;
            if (peer == this) {
                peer = null;
            }
        }
    }

    /* ==================== 收尾 ==================== */

    static void stopAll() {
        for (DirectUdpServer instance : INSTANCES) {
            instance.stop();
        }
        INSTANCES.clear();
    }

    private void stop() {
        running = false;
        UdpPeer current = peer;
        peer = null;
        if (current != null) {
            current.closed = true;
        }
        DatagramSocket socket = this.socket;
        this.socket = null;
        if (socket != null) {
            socket.close();
        }
    }
}
