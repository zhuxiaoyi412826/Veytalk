package com.im.remote.agent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 直连通道的被控端协调者：握手鉴权、链路选路、流量计数、候选地址发布。
 *
 * <h2>它在整个直连里的位置</h2>
 *
 * <p>{@link DirectTcpServer}（局域网 WebSocket / 裸帧）与 {@link DirectUdpServer}（打洞）
 * 只负责把字节变成「完整 DXP 报文」，本类负责剩下的所有决定：
 * 这个 HELLO 能不能信、信了之后当前链路是谁、这条消息该从直连走还是该回中继、
 * 这条会话在直连上实际收发了多少字节。两个服务端共用同一份判定，
 * 才不会出现在 TCP 上放过去、UDP 上拒掉（或反之）的口径分裂。
 *
 * <h2>握手：token + 密钥证明，缺一不可</h2>
 *
 * <p>监听口一旦被拒方摸到端口就无法隐藏，所以「能连上」本身不能等于「能控制」。
 * 两道门禁：
 * <ol>
 *   <li>{@code token}：服务端在 session-start 里同时下发给两端的一次性随机串，
 *       会话结束即失效，拿不到它就进不来；</li>
 *   <li>{@code mac}：证明对方真的持有会话 AES 密钥，而不是只嗅到了票据。
 *       有密钥时算 {@code HMAC-SHA256(aesKey, "dxp-hello|sid|cnonce|token")}，
 *       dev 环境 AES 关闭（没有密钥可用）则退化为
 *       {@code SHA-256("dxp-hello|sid|cnonce|token")}——两种算法都掺了 token，
 *       所以退化版仍然要求攻击者知道票据。</li>
 * </ol>
 *
 * <p>HELLO 方向固定为「控制端发起」：被控端在 NAT 后，只有控制端主动发包才可能打洞成功。
 *
 * <h2>失败即回落，不是失败即中断</h2>
 *
 * <p>直连任何一步失败（防火墙拒连、对称 NAT、对端掉线、ARQ 连续 4 轮无进展）
 * 都只是把这条消息改走中继：中继 WS 全程保持打开，所以用户感知不到链路切换，
 * 最坏结果退化成「和没做直连之前一样」。因此本类所有 send* 方法都返回 boolean，
 * 语义是「我接手了没有」，false 时调用方必须自己走中继，绝不吞消息。
 */
public final class DirectChannel {

    public static final String PATH_TCP = "tcp";
    public static final String PATH_UDP = "udp";

    /** 一条已握手的对端链路；由两个服务端各自的实现类提供 */
    public interface Link {
        /** {@link #PATH_TCP} / {@link #PATH_UDP} */
        String path();

        /** 对端地址，仅用于日志与审计 */
        String peer();

        /** 不可靠通道（画面）：排不下就丢，返回 false 表示本帧没送出去 */
        boolean sendScreen(byte kind, byte[] message);

        /** 可靠通道（信封 + 文件块）：UDP 侧走 ARQ，TCP 侧直接写 socket */
        boolean sendReliable(byte kind, byte[] message);

        boolean alive();

        void close(String reason);
    }

    /** 握手结果：ack 一定回给对端（带被拒原因），accept 才建链路；mtu 为本端要求的单报文上限 */
    public record Hello(String ackJson, boolean accept, int mtu) {
    }

    private static final int MAX_REJECTS_PER_MINUTE = 60;

    private final AgentClient client;
    private final AgentConfig config;
    private volatile DirectTcpServer tcp;
    private volatile DirectUdpServer udp;

    private volatile Link link;
    /** 上一次成功的 ACK 原文：控制端没收到 ACK 会周期性重发 HELLO，幂等重发才能既不重建 ARQ 又能过验签 */
    private volatile String lastAck;
    private volatile long sid;
    private volatile String token = "";
    private volatile byte[] aesKey;
    private volatile int mtu = 1200;
    /** UDP 打洞后由反射服务回显的公网地址，随候选一起发给控制端；未打洞为空 */
    private volatile String publicHost = "";
    private volatile int publicPort;

    /** 本端收到的直连字节（= 控制端发出的） */
    private final AtomicLong recvBytes = new AtomicLong();
    /** 本端发出的直连字节（= 控制端收到的） */
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile long rejectWindowStart;
    private final AtomicLong rejectWindowCount = new AtomicLong();

    private volatile boolean started;

    public DirectChannel(AgentClient client, AgentConfig config) {
        this.client = client;
        this.config = config;
    }

    /** 被控端本地开关：关掉后无论服务端怎么下发都不监听、不握手 */
    public boolean enabled() {
        return config.directEnabled();
    }

    public boolean available() {
        return enabled() && sid != 0 && !token.isEmpty();
    }

    public boolean connected() {
        Link current = link;
        return current != null && current.alive();
    }

    public String path() {
        Link current = link;
        return current == null ? "" : current.path();
    }

    /**
     * 给 UI 状态行的一句话：监听口开在哪、现在通没通、被拒过几次。
     *
     * <p>握手是控端发起的，本端没有业务回调点可挂，只能把这行做成可读快照由 UI 定时抓；
     * 而现场排查时最先要的恰恰就是这几个数。
     */
    public String summary() {
        if (!enabled()) {
            return "直连关";
        }
        if (sid == 0) {
            return "直连就绪(无会话)";
        }
        Link current = link;
        if (current == null || !current.alive()) {
            return "直连待握手(TCP " + config.directTcpPort() + "/UDP " + config.directUdpPort() + ")";
        }
        long count = rejected.get();
        // 字节只算本端直连链路的，与中继计数各走各的（服务端落库时才合算）
        return "直连已建立 " + current.path() + " ← " + current.peer()
                + " 收发 " + (recvBytes.get() + sentBytes.get()) / 1024 + "KB"
                + (count == 0 ? "" : " 拒" + count + "次");
    }

    /* ==================== 生命周期 ==================== */

    /**
     * 会话建立时调用：必要时拉起两个监听口，并按需发起 UDP 打洞预热。
     *
     * <p>监听口一旦起来就活到进程结束（{@link #shutdown}）——每来一个会话就 bind 一次
     * 会碰上 TIME_WAIT 导致第二次建会话直连静默失败，这种「时好时坏」最难查。
     * 是否接受握手由 {@link #hello} 里的 sid/token 判定，与端口是否开放无关。
     */
    public void onSessionStart(long sessionId, Map<String, Object> direct, byte[] sessionAesKey) {
        this.sid = sessionId;
        recvBytes.set(0);
        sentBytes.set(0);
        this.link = null;
        this.aesKey = sessionAesKey;
        if (!enabled() || direct == null || direct.isEmpty() || !MiniJson.bool(direct, "enabled", false)) {
            this.token = "";
            return;
        }
        this.token = MiniJson.str(direct, "token") == null ? "" : MiniJson.str(direct, "token");
        this.mtu = Math.max(512, MiniJson.integer(direct, "mtu", 1200));
        if (this.token.isEmpty()) {
            client.log("直连未启用：服务端未下发票据（检查 im.remote.direct.enabled）");
            return;
        }
        ensureServers();
        client.log("直连监听就绪: TCP " + config.directTcpPort() + " / UDP " + config.directUdpPort()
                + "（局域网 " + String.join(",", lanAddresses()) + "）");
        // 先把局域网候选发出去，控制端可以立即试 LAN 档；公网映射拿到了再补一帧，
        // 两帧都是幂等信息，控制端已连上时会直接忽略后一帧
        publishCandidates();
        DirectUdpServer server = udp;
        String punchHost = MiniJson.str(direct, "punchHost");
        if (server != null && MiniJson.bool(direct, "udp", false) && punchHost != null && !punchHost.isBlank()) {
            server.punch(punchHost, MiniJson.integer(direct, "punchPort", 8947));
        }
    }

    /** 把本端可连地址交给控制端（中继透传，不过直连本身） */
    public void publishCandidates() {
        if (sid != 0 && enabled() && !token.isEmpty()) {
            client.sendEnvelope("direct-candidates", sid, candidates());
        }
    }

    private synchronized void ensureServers() {
        if (started) {
            return;
        }
        started = true;
        if (config.directAllowLan()) {
            try {
                tcp = new DirectTcpServer(this, config.directTcpPort());
                tcp.start();
            } catch (Exception e) {
                // 端口被占：局域网直连这一档不可用，中继与 UDP 都不受影响
                tcp = null;
                client.log("直连 TCP 监听启动失败（局域网直连不可用）: " + e.getMessage());
            }
        }
        try {
            udp = new DirectUdpServer(this, config.directUdpPort());
            udp.start();
        } catch (Exception e) {
            udp = null;
            client.log("直连 UDP 监听启动失败（打洞直连不可用）: " + e.getMessage());
        }
    }

    /** 会话结束：断链路、清密钥与票据（监听口保留，等下一个会话） */
    public void onSessionEnd(String reason) {
        Link current = link;
        this.link = null;
        if (current != null && current.alive()) {
            try {
                current.close(reason == null ? "session-end" : reason);
            } catch (Exception ignored) {
                // 关闭失败无副作用，链路已判死
            }
        }
        this.sid = 0;
        this.token = "";
        this.aesKey = null;
        this.lastAck = null;
        this.publicHost = "";
        this.publicPort = 0;
    }

    /** 进程退出前调用 */
    public void shutdown() {
        onSessionEnd("agent-stop");
        DirectTcpServer.stopAll();
        DirectUdpServer.stopAll();
        started = false;
    }

    /* ==================== 握手 ==================== */

    /**
     * 处理一个 HELLO 报文。
     *
     * @param helloJson 对端 HELLO 的 JSON 正文
     * @param path      {@link #PATH_TCP} / {@link #PATH_UDP}
     * @param peer      对端地址（日志用）
     * @return 要回给对端的 ACK 与是否接受
     */
    public Hello hello(String helloJson, String path, String peer) {
        try {
            return doHello(helloJson, path, peer);
        } catch (Exception e) {
            return new Hello(ack(false, "bad-hello", 0, ""), false, 0);
        }
    }

    private Hello doHello(String helloJson, String path, String peer) {
        Map<String, Object> hello = MiniJson.parseObject(helloJson);
        long theirSid = MiniJson.lng(hello, "sid", 0);
        String cnonce = MiniJson.str(hello, "cnonce");
        int theirMtu = MiniJson.integer(hello, "mtu", mtu);
        if (!enabled() || sid == 0 || token.isEmpty()) {
            // 常见于「控制端在 Agent 建会话前就抢跑」：它按预算重试即可，不是攻击
            return reject("no-session", peer);
        }
        if (theirSid != sid) {
            return reject("sid-mismatch", peer);
        }
        if (rateLimited()) {
            return reject("rate-limited", peer);
        }
        if (!token.equals(MiniJson.str(hello, "token"))) {
            return reject("bad-token", peer);
        }
        if (!proofMatches(aesKey, token, theirSid, cnonce, MiniJson.str(hello, "mac"))) {
            return reject("bad-mac", peer);
        }
        Link current = link;
        if (current != null && current.alive()) {
            // 一台被控机同一时刻只服务一个控制端，与中继侧的「已有会话直接拒邀」保持一致
            return reject("busy", peer);
        }
        String anonce = randomHex(12);
        // TCP 档一条消息就是一个报文（WebSocket/长度前缀自带边界），不必也不应该分片；
        // UDP 档取两端较小值，超过即分片交由 ARQ 处理
        int negotiated = path.equals(PATH_TCP) ? Dxp.MAX_MTU : Math.max(512, Math.min(theirMtu, mtu));
        String ack = ack(true, "", negotiated, anonce);
        this.lastAck = ack;
        client.log("直连握手通过(" + path + "): " + peer);
        return new Hello(ack, true, negotiated);
    }

    private Hello reject(String reason, String peer) {
        rejected.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - rejectWindowStart > 60_000) {
            rejectWindowStart = now;
            rejectWindowCount.set(0);
        }
        rejectWindowCount.incrementAndGet();
        if (!"rate-limited".equals(reason) && !"no-session".equals(reason)) {
            // 抢跑与限流不刷日志，其余（票据错、密钥错）是安全线索，必须留痕
            client.log("直连握手被拒: reason=" + reason + " peer=" + peer);
            client.sendAudit("direct-rejected", "reason=" + reason + ", peer=" + peer);
        }
        return new Hello(ack(false, reason, 0, ""), false, 0);
    }

    /** 同一对端的 HELLO 重发：原样重发上次 ACK，不重建链路（重建会丢 ARQ 序号） */
    public String replayAck(String peer) {
        Link current = link;
        if (current == null || !current.alive() || lastAck == null || !current.peer().equals(peer)) {
            return null;
        }
        return lastAck;
    }

    /** 打洞过程线索（只进日志，不影响主流程） */
    void logPunch(String message) {
        client.log("直连打洞: " + message);
    }

    /** 无鉴权信息也绝不做 HMAC 运算：先数请求数，再多就一律不理 */
    private boolean rateLimited() {
        long now = System.currentTimeMillis();
        if (now - rejectWindowStart > 60_000) {
            rejectWindowStart = now;
            rejectWindowCount.set(0);
        }
        return rejectWindowCount.get() > MAX_REJECTS_PER_MINUTE;
    }

    private String ack(boolean ok, String reason, int negotiatedMtu, String anonce) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", 1);
        body.put("kind", "helloAck");
        body.put("ok", ok);
        body.put("sid", String.valueOf(sid));
        if (reason != null && !reason.isEmpty()) {
            body.put("reason", reason);
        }
        if (ok) {
            body.put("anonce", anonce);
            body.put("mtu", negotiatedMtu);
            body.put("mac", proof(aesKey, token, sid, anonce, "dxp-ack"));
            body.put("deviceName", config.deviceName());
        }
        return MiniJson.write(body);
    }

    /** 链路建好由服务端回调；第二条链路永远进不来 */
    void attach(Link created) {
        Link old = link;
        if (old != null && old.alive()) {
            try {
                old.close("replaced");
            } catch (Exception ignored) {
                // 旧链路本来就要死了
            }
        }
        this.link = created;
        client.log("直连已建立: path=" + created.path() + " peer=" + created.peer());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", created.path());
        data.put("peer", created.peer());
        client.sendEnvelope("direct-up", sid, data);
    }

    void detach(Link dead) {
        if (link != dead) {
            return;
        }
        link = null;
        client.log("直连已断开: path=" + dead.path() + "（后续消息回到中继）");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", dead.path());
        data.put("peer", dead.peer());
        data.put("reason", "link-lost");
        client.sendEnvelope("direct-failed", sid, data);
    }

    /* ==================== 密钥证明 ==================== */

    /** 与前端 dxp.js 逐字段对齐：算法串、拼接顺序、Base64 口径任何一处不一致都会表现为「握手失败」 */
    static String proof(byte[] aesKey, String token, long sid, String nonce, String prefix) {
        String material = prefix + "|" + sid + "|" + nonce + "|" + token;
        try {
            if (aesKey != null && aesKey.length == 32) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(aesKey, "HmacSHA256"));
                return Base64.getEncoder().encodeToString(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
            }
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("密钥证明计算失败: " + e.getMessage(), e);
        }
    }

    /** 定长比较：握手失败不能通过耗时差异被探测 */
    static boolean proofMatches(byte[] aesKey, String token, long sid, String nonce, String expected) {
        if (nonce == null || nonce.isEmpty() || expected == null || expected.isEmpty()) {
            return false;
        }
        byte[] mine = proof(aesKey, token, sid, nonce, "dxp-hello").getBytes(StandardCharsets.UTF_8);
        byte[] theirs = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(mine, theirs);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buffer);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /* ==================== 收发 ==================== */

    /** 可靠通道：文本信封（result/error）与非画面二进制帧 */
    public boolean sendReliable(byte kind, byte[] message) {
        Link current = link;
        if (current == null || !current.alive()) {
            return false;
        }
        if (!current.sendReliable(kind, message)) {
            detach(current);
            return false;
        }
        return true;
    }

    /** 画面帧：丢了等下一帧，绝不重传，也绝不因为发不出去就断开直连 */
    public boolean sendScreen(byte kind, byte[] message) {
        Link current = link;
        if (current == null || !current.alive()) {
            return false;
        }
        return current.sendScreen(kind, message);
    }

    /** 服务端 → 本端的候选交换：Agent 把自己的可连地址发过去，控制端据此按阶梯尝试 */
    public Map<String, Object> candidates() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lan", config.directAllowLan() ? lanAddresses() : List.of());
        data.put("tcpPort", config.directTcpPort());
        data.put("udpPort", config.directUdpPort());
        data.put("mtu", mtu);
        if (!publicHost.isEmpty()) {
            data.put("publicHost", publicHost);
            data.put("publicPort", publicPort);
        }
        return data;
    }

    /** 反射服务回显的公网映射（DirectUdpServer 打洞后回填） */
    void setPublicAddress(String host, int port) {
        this.publicHost = host == null ? "" : host;
        this.publicPort = port;
        if (!this.publicHost.isEmpty()) {
            // 拿到了控制端唯一能「打进来」的地址，补发一次候选
            publishCandidates();
        }
    }

    /** 由服务端在收到/发出报文时调用，统一按「链路上真实字节」计，与中继侧口径一致 */
    void accountRecv(int bytes) {
        recvBytes.addAndGet(bytes);
    }

    void accountSent(int bytes) {
        sentBytes.addAndGet(bytes);
    }

    long rejectedCount() {
        return rejected.get();
    }

    /** 心跳里捎带：既让服务端的空闲巡检知道「直连还活着」，也让流量条实时跳动 */
    public void reportStats() {
        Link current = link;
        if (current == null || sid == 0) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("down", recvBytes.get());
        data.put("up", sentBytes.get());
        data.put("path", current.path());
        data.put("peer", current.peer());
        client.sendEnvelope("direct-stats", sid, data);
    }

    /** 直连收到的消息最终要落回与中继完全相同的处理入口 */
    void deliverMessage(byte kind, byte[] message) {
        if (kind == Dxp.KIND_TEXT) {
            client.receiveDirectText(new String(message, StandardCharsets.UTF_8));
        } else {
            client.receiveDirectBinary(message);
        }
    }

    /** 控制端要的「这台机器能直连的地址」，只报站点内网地址，链路本地与虚拟网卡不算 */
    static List<String> lanAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (java.net.InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        String text = addr.getHostAddress();
                        if (text.indexOf(':') < 0 && !result.contains(text)) {
                            result.add(text);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败只是少一档直连能力，回中继即可
        }
        return result;
    }
}
