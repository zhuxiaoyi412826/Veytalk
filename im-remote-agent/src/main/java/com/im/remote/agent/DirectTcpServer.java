package com.im.remote.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网直连服务端：一个 TCP 端口同时兼容两种客户端。
 *
 * <h2>为什么要两种</h2>
 *
 * <ul>
 *   <li><b>WebSocket 模式</b>：浏览器只能 {@code new WebSocket(...)}，无法发裸 TCP，
 *       所以手机/PC 浏览器控制端必须走这条；代价是 Agent 要自己实现 RFC6455 的
 *       握手与帧编解码（约 200 行，纯 JDK 完全够，不必为此起 Netty 或引第三方）；</li>
 *   <li><b>裸帧模式</b>：Electron 主进程能开真 socket，省掉掩码与帧头，
 *       且不受浏览器混合内容限制，是同网段桌面互控的最短路径。</li>
 * </ul>
 *
 * <p>两模式共用一条判据：连接建立后先读 4 字节，{@code "GET "} 开头即 HTTP 升级请求，
 * 否则按「4 字节大端长度 + DXP 报文」的裸帧读。这样不需要第二个端口，
 * 也就只需要在防火墙上放行一个端口。
 *
 * <p>本端不做分片：一条 WS 消息 / 一个长度前缀帧就是一个完整 DXP 报文，
 * 握手 ACK 里回 {@code mtu=1MB} 明确告知对端「别切片」。TCP 自带可靠有序，
 * 再叠一层 ARQ 只会重复劳动。
 */
public final class DirectTcpServer {

    private static final List<DirectTcpServer> INSTANCES = new CopyOnWriteArrayList<>();
    /** 同时接受的半开/已连直连数：握手失败的探测连接不该无限堆积线程 */
    private static final int MAX_CONNECTIONS = 8;
    /** 单报文上限，与 Dxp.MAX_MTU 同口径，超过即判畸形并断开 */
    private static final int MAX_PACKET = 2 * 1024 * 1024;
    private static final String WS_GUID = "258EAFA5-E91C-44DA-95C9-A0711C156422";

    private final DirectChannel channel;
    private final int port;
    private final AtomicInteger live = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile Thread acceptor;

    DirectTcpServer(DirectChannel channel, int port) {
        this.channel = channel;
        this.port = port;
    }

    void start() throws IOException {
        ServerSocket bound = new ServerSocket();
        // 复用地址：会话切换时上一个连接可能还在 TIME_WAIT，不复用会让第二次直连静默失败
        bound.setReuseAddress(true);
        bound.bind(new InetSocketAddress(port), 16);
        server = bound;
        acceptor = new Thread(this::acceptLoop, "direct-tcp-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
        INSTANCES.add(this);
    }

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                if (live.get() >= MAX_CONNECTIONS) {
                    closeQuietly(socket);
                    continue;
                }
                live.incrementAndGet();
                Thread worker = new Thread(() -> serve(socket), "direct-tcp-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                // accept 抛异常基本等于 socket 已关（会话收尾/进程退出），正常退出
                return;
            }
        }
    }

    private void serve(Socket socket) {
        String peer = socket.getRemoteSocketAddress() == null ? "?" : String.valueOf(socket.getRemoteSocketAddress());
        try {
            socket.setTcpNoDelay(true);
            // 先阻塞读 4 字节判模式，再把这 4 字节接回去——两种模式都不允许丢预读字节
            InputStream plain = new java.io.BufferedInputStream(socket.getInputStream(), 64 * 1024);
            byte[] head = readBytes(plain, 4);
            InputStream in = new SequenceInputStream(new ByteArrayInputStream(head), plain);
            DirectMode mode = head[0] == 'G' && head[1] == 'E' && head[2] == 'T' && head[3] == ' '
                    ? DirectMode.WEBSOCKET : DirectMode.RAW;
            new Connection(socket, mode, peer).run(in, head);
        } catch (Exception e) {
            // 单个连接的异常只影响这一条链路，直连失败自然回落中继
        } finally {
            live.decrementAndGet();
            closeQuietly(socket);
        }
    }

    private enum DirectMode { WEBSOCKET, RAW }

    /* ==================== 单连接 ==================== */

    /** 一条已建立的直连 TCP 会话：握手 → 收帧上抛 / 发帧下写 */
    private final class Connection implements DirectChannel.Link {
        private final Socket socket;
        private final DirectMode mode;
        private final String peer;
        private final OutputStream out;
        /** 保护 out 的写入：画面线程与文件线程可能同时发 */
        private final Object writeLock = new Object();
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        private int outMsgId;
        private volatile boolean alive = true;
        private volatile boolean handshaked;

        Connection(Socket socket, DirectMode mode, String peer) throws IOException {
            this.socket = socket;
            this.mode = mode;
            this.peer = peer;
            this.out = new java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        }

        void run(InputStream in, byte[] head) throws Exception {
            if (mode == DirectMode.WEBSOCKET) {
                websocketHandshake(in);
            }
            byte[] pending = mode == DirectMode.RAW ? head : null;
            while (alive) {
                byte[] packet = mode == DirectMode.WEBSOCKET ? readWsFrame(in) : readRawPacket(in, pending);
                pending = null;
                if (packet == null) {
                    break;
                }
                channel.accountRecv(packet.length);
                Dxp.Packet parsed = Dxp.decode(packet, packet.length);
                if (parsed == null) {
                    continue;
                }
                if (!onPacket(parsed)) {
                    break;
                }
            }
        }

        /** 裸帧模式：[4B 大端长度][DXP 报文]；长度前 4 字节可能已在判模式时读过，由 pending 带回来 */
        private byte[] readRawPacket(InputStream in, byte[] pending) throws IOException {
            long declared;
            if (pending != null) {
                declared = ByteBuffer.wrap(pending).getInt() & 0xFFFF_FFFFL;
            } else {
                declared = readN(in, 4);
            }
            if (declared <= 0 || declared > MAX_PACKET) {
                return null;
            }
            return readBytes(in, (int) declared);
        }

        /** 返回 false 表示连接应结束 */
        private boolean onPacket(Dxp.Packet packet) throws IOException {
            if (packet.type() == Dxp.TYPE_HELLO) {
                if (handshaked) {
                    return false;
                }
                DirectChannel.Hello result = channel.hello(Dxp.helloJson(packet.body()), DirectChannel.PATH_TCP, peer);
                sendControl(Dxp.TYPE_HELLO_ACK, result.ackJson());
                if (!result.accept()) {
                    return false;
                }
                handshaked = true;
                channel.attach(this);
                return true;
            }
            if (!handshaked) {
                // 没握手就来数据：直接断，不给对端任何反馈，探测脚本据此只能得到 RST
                return false;
            }
            switch (packet.type()) {
                case Dxp.TYPE_PING -> sendControl(Dxp.TYPE_PONG, (byte[]) null);
                case Dxp.TYPE_CLOSE -> {
                    return false;
                }
                case Dxp.TYPE_DATA -> {
                    Dxp.Fragment fragment = Dxp.fragment(packet.body());
                    if (fragment != null && fragment.count() == 1) {
                        channel.deliverMessage(fragment.kind(), fragment.data());
                    }
                    // TCP 档不该收到多分片（协商 mtu=1MB）；真收到了就忽略，
                    // 让对端的 ARQ/重传逻辑自己发现问题，而不是在这里拼出半截消息
                }
                default -> {
                    // ACK 等 ARQ 报文在 TCP 档无意义，忽略
                }
            }
            return true;
        }

        /* ---------- 发送 ---------- */

        @Override
        public String path() {
            return DirectChannel.PATH_TCP;
        }

        @Override
        public String peer() {
            return peer;
        }

        @Override
        public boolean alive() {
            return alive && handshaked && !socket.isClosed();
        }

        @Override
        public boolean sendScreen(byte kind, byte[] message) {
            return writeData(Dxp.CH_SCREEN, kind, message);
        }

        @Override
        public boolean sendReliable(byte kind, byte[] message) {
            return writeData(Dxp.CH_RELIABLE, kind, message);
        }

        private boolean writeData(byte channelType, byte kind, byte[] message) {
            int msgId;
            synchronized (this) {
                msgId = ++outMsgId;
            }
            byte[] packet = Dxp.encode(Dxp.TYPE_DATA, channelType, Dxp.FLAG_LAST, msgId, 0,
                    Dxp.fragmentBody(0, 1, kind, message, 0, message.length));
            return writePacket(packet);
        }

        private void sendControl(byte type, Object body) throws IOException {
            byte[] payload = body == null ? null
                    : (body instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : (byte[]) body);
            writePacket(Dxp.encode(type, Dxp.CH_CONTROL, (byte) 0, 0, 0, payload));
        }

        private boolean writePacket(byte[] packet) {
            if (!alive) {
                return false;
            }
            try {
                synchronized (writeLock) {
                    if (mode == DirectMode.WEBSOCKET) {
                        writeWsFrame(packet);
                    } else {
                        out.write(ByteBuffer.allocate(4).putInt(packet.length).array());
                        out.write(packet);
                        out.flush();
                    }
                    channel.accountSent(packet.length + (mode == DirectMode.WEBSOCKET ? 14 : 4));
                }
                return true;
            } catch (Exception e) {
                alive = false;
                channel.detach(this);
                return false;
            }
        }

        @Override
        public void close(String reason) {
            alive = false;
            try {
                synchronized (writeLock) {
                    if (mode == DirectMode.WEBSOCKET) {
                        writeWsFrame(closeFrame());
                    }
                }
            } catch (Exception ignored) {
                // 关闭前的告警帧发不出去也无所谓
            }
            closeQuietly(socket);
        }

        /* ---------- WebSocket 帧 ---------- */

        private void websocketHandshake(InputStream in) throws IOException {
            String request = readHttpHeaders(in);
            String key = headerValue(request, "sec-websocket-key");
            if (key == null) {
                throw new IOException("not a websocket upgrade");
            }
            String accept;
            try {
                accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key.trim() + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-1 unavailable", e);
            }
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** 读到 \r\n\r\n 为止；只取升级判断需要的头，其余原样丢弃 */
        private String readHttpHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int matched = 0;
            int current;
            while ((current = in.read()) >= 0) {
                buffer.write(current);
                boolean want = (matched & 1) == 0 ? current == '\r' : current == '\n';
                if (want) {
                    matched++;
                    if (matched == 4) {
                        break;
                    }
                } else {
                    matched = current == '\r' ? 1 : 0;
                }
                if (buffer.size() > 16 * 1024) {
                    throw new IOException("http header too large");
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1).toLowerCase();
        }

        private static String headerValue(String lowerRequest, String name) {
            for (String line : lowerRequest.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equals(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        /**
         * 读一个 WS 数据帧的完整载荷（自动解掩码、处理续帧）。
         * 返回 null 表示对端已关闭连接。
         */
        private byte[] readWsFrame(InputStream in) throws IOException {
            while (true) {
                int first = in.read();
                if (first < 0) {
                    return null;
                }
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 0x0F;
                int second = in.read();
                if (second < 0) {
                    return null;
                }
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7F;
                if (length == 126) {
                    length = readN(in, 2);
                } else if (length == 127) {
                    long wide = readN(in, 8);
                    if (wide > MAX_PACKET) {
                        return null;
                    }
                    length = wide;
                }
                if (length > MAX_PACKET) {
                    return null;
                }
                byte[] maskKey = masked ? readBytes(in, 4) : null;
                byte[] payload = readBytes(in, (int) length);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= maskKey[i & 3];
                    }
                }
                switch (opcode) {
                    case 0x8 -> {
                        return null;
                    }
                    case 0x9 -> {
                        // 控制帧必须立刻回，否则对端会判链路已死
                        writePacketMasked(0xA, new byte[0]);
                        continue;
                    }
                    case 0xA -> {
                        continue;
                    }
                    default -> {
                        if (opcode == 0x0) {
                            fragments.write(payload);
                        } else {
                            fragments.reset();
                            fragments.write(payload);
                        }
                        if (!fin) {
                            continue;
                        }
                        byte[] message = fragments.toByteArray();
                        fragments.reset();
                        return message;
                    }
                }
            }
        }

        private void writePacketMasked(int opcode, byte[] payload) throws IOException {
            synchronized (writeLock) {
                writeWsFrame(opcode, payload);
            }
        }

        private void writeWsFrame(byte[] payload) throws IOException {
            writeWsFrame(0x2, payload);
        }

        /** 服务端→客户端的帧不掩码（RFC6455 要求，浏览器会拒收掩码帧） */
        private void writeWsFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream builder = new ByteArrayOutputStream(payload.length + 14);
            builder.write(0x80 | opcode);
            if (payload.length < 126) {
                builder.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                builder.write(126);
                builder.write((payload.length >> 8) & 0xFF);
                builder.write(payload.length & 0xFF);
            } else {
                builder.write(127);
                builder.write(ByteBuffer.allocate(8).putLong(payload.length).array());
            }
            builder.write(payload);
            byte[] frame = builder.toByteArray();
            out.write(frame);
            out.flush();
        }

        private static byte[] closeFrame() throws IOException {
            byte[] message = "bye".getBytes(StandardCharsets.UTF_8);
            byte[] status = ByteBuffer.allocate(2).putShort((short) 1000).array();
            byte[] payload = new byte[status.length + message.length];
            System.arraycopy(status, 0, payload, 0, status.length);
            System.arraycopy(message, 0, payload, status.length, message.length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 14);
            out.write(0x80 | 0x8);
            out.write(payload.length);
            out.write(payload);
            return out.toByteArray();
        }
    }

    private static long readN(InputStream in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int current = in.read();
            if (current < 0) {
                throw new IOException("EOF");
            }
            value = (value << 8) | current;
        }
        return value;
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("EOF");
            }
            offset += read;
        }
        return buffer;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无副作用
        }
    }

    static void stopAll() {
        for (DirectTcpServer instance : INSTANCES) {
            instance.stop();
        }
        INSTANCES.clear();
    }

    private void stop() {
        ServerSocket current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // 进程正在退出
            }
        }
        Thread thread = acceptor;
        acceptor = null;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
