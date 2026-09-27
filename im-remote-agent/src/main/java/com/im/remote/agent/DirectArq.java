package com.im.remote.agent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 直连可靠通道：Go-Back-N 自动重传（ARQ）+ 分片重组，纯 JDK 实现。
 *
 * <h2>为什么非要做这么一层</h2>
 *
 * <p>直连的两档通道里，TCP/WebSocket 档自带可靠性，UDP 档什么都没有。
 * 而「文件传输也必须走直连」是明确需求，UDP 上不分片不重传就等于传坏文件，
 * 所以打洞这条路必须自带 ARQ。选 Go-Back-N 而不是 SACK：屏幕画面走不可靠通道
 * （丢了等下一帧，绝不重传），可靠通道上只有指令、信封和文件块，
 * 一个丢包重发整个窗口（64 包 = 约 76KB）的代价可接受，换来接收端只记一个序号。
 *
 * <h2>三条通道的语义</h2>
 * <ul>
 *   <li>{@link Dxp#CH_SCREEN} 画面：不重传、不保序，缺片即整帧作废（下一关键帧自愈）；</li>
 *   <li>{@link Dxp#CH_RELIABLE} 可靠：信封 + 文件块共用<b>同一条有序流</b>。
 *       刻意不让信封另走一路——若 {@code file-put} 元数据比另一条路先行发出的数据块晚到，
 *       接收端会因为「未登记该 transferId」直接丢块，这类 bug 事后极难归因；</li>
 *   <li>{@link Dxp#CH_CONTROL} 握手/心跳：不编号、不重传，靠上层周期性重发 HELLO。</li>
 * </ul>
 *
 * <p>失活判定：连续 {@link #MAX_TIMEOUT_STREAK} 次超时重传仍无进展即 {@code healthy=false}，
 * 上层据此把整条会话原子回落到中继，不做「逐条消息各选各路」的混发，理由同上。
 *
 * <p>并发模型：所有状态在同一把锁下变更；{@link PacketSink} 在锁内调用（UDP 发送是微秒级，
 * 重传窗口最多 64 包），用一点吞吐换取「不会因线程交错而发出乱序序号」这条更值钱的性质。
 * 唯一在锁外做的是把重组完成的消息交给 listener，避免回调再进本对象造成重入。
 */
final class DirectArq {

    interface PacketSink {
        void send(byte[] packet);
    }

    interface MessageListener {
        void onMessage(byte kind, byte[] message);
    }

    /** 未确认的可靠通道包数上限 */
    private static final int WINDOW = 64;
    /** 可靠通道待发队列上限（含窗口），约 150KB 在途，够填满带宽时延积又不至于内存失控 */
    private static final int RELIABLE_QUEUE = WINDOW * 2;
    /** 画面通道待发队列上限：积压超过 4 帧就直接丢，旧画面没有保留价值 */
    private static final int SCREEN_QUEUE = 4;
    private static final int MAX_TIMEOUT_STREAK = 4;
    private static final long RTO_BASE_MS = 250;
    private static final long RTO_MAX_MS = 1000;
    /** 可靠队列排空等待上限：超时即判直连拥堵，交上层回落 */
    private static final long SEND_BLOCK_MS = 2000;
    /** 画面槽位过期时长：一帧最多 400KB / 1182B ≈ 340 片，正常几十毫秒收完，超 400ms 判定残缺 */
    private static final long SCREEN_SLOT_STALE_MS = 400;

    private final int mtu;
    private final PacketSink sink;
    private final MessageListener listener;

    /** 可靠通道待发（分片后的报文，seq 字段为 0，进窗时补写） */
    private final Deque<byte[]> reliableWaiting = new ArrayDeque<>();
    /** 画面/控制待发：不编号，优先于可靠包出队，避免被大文件传输饿死 */
    private final Deque<byte[]> screenWaiting = new ArrayDeque<>();
    private final Deque<Sent> unacked = new ArrayDeque<>();

    private long nextSeq = 1;
    /** 对端已确认到的最大连续 seq（本端窗口左边界） */
    private long lastContiguous;
    /** 本端期望收到的下一个 seq（左边界 + 1） */
    private long expectedSeq = 1;
    private int rtoMs = (int) RTO_BASE_MS;
    private int timeoutStreak;
    private boolean ackDue;
    private boolean retransmitHint;
    private int outMsgId;
    private volatile boolean healthy = true;

    private final Slot reliableSlot = new Slot();
    private final Slot screenSlot = new Slot();

    private record Sent(long seq, byte[] packet, long sentAtMs) {
    }

    /** 分片重组槽位 */
    private static final class Slot {
        int msgId = -1;
        int kind;
        int expectedIndex;
        int count;
        boolean poisoned;
        long updatedAtMs;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        void reset() {
            msgId = -1;
            kind = 0;
            expectedIndex = 0;
            count = 0;
            poisoned = false;
            updatedAtMs = 0;
            buffer.reset();
        }
    }

    DirectArq(int mtu, PacketSink sink, MessageListener listener) {
        this.mtu = Math.min(Math.max(mtu, 512), Dxp.MAX_MTU);
        this.sink = sink;
        this.listener = listener;
    }

    boolean healthy() {
        return healthy;
    }

    void markUnhealthy() {
        healthy = false;
    }

    /* ==================== 发送 ==================== */

    /**
     * 可靠通道发送一条完整报文。队列满时最多阻塞 {@link #SEND_BLOCK_MS}，
     * 超时返回 false——调用方据此把会话整体切回中继，而不是无限期挂住文件线程。
     */
    boolean sendReliable(byte kind, byte[] message) {
        return enqueue(Dxp.CH_RELIABLE, kind, message, true);
    }

    /** 画面通道：永不阻塞、永不重传，排不下直接丢（丢帧远好过卡住截屏线程） */
    boolean sendScreen(byte kind, byte[] message) {
        return enqueue(Dxp.CH_SCREEN, kind, message, false);
    }

    private boolean enqueue(byte channel, byte kind, byte[] message, boolean blocking) {
        List<byte[]> packets = split(channel, kind, message);
        synchronized (this) {
            if (blocking) {
                long deadline = System.currentTimeMillis() + SEND_BLOCK_MS;
                while (reliableWaiting.size() + unacked.size() > RELIABLE_QUEUE) {
                    if (!healthy || System.currentTimeMillis() > deadline) {
                        return false;
                    }
                    try {
                        wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                reliableWaiting.addAll(packets);
            } else {
                if (screenWaiting.size() > SCREEN_QUEUE * 2) {
                    // 积压过深：清掉整帧旧的，只保留最新一帧，避免画面延迟无限累积
                    return false;
                }
                screenWaiting.addAll(packets);
            }
            pump();
        }
        return true;
    }

    /** 把一条消息切成若干 DATA 报文（seq 待进窗时补写） */
    private List<byte[]> split(byte channel, byte kind, byte[] message) {
        int capacity = Dxp.payloadCapacity(mtu);
        int count = Dxp.fragmentCount(mtu, message.length);
        int msgId;
        synchronized (this) {
            msgId = ++outMsgId;
        }
        List<byte[]> packets = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int offset = index * capacity;
            int length = Math.min(capacity, message.length - offset);
            byte[] body = Dxp.fragmentBody(index, count, kind, message, offset, length);
            byte flags = (byte) (index == count - 1 ? Dxp.FLAG_LAST : 0);
            packets.add(Dxp.encode(Dxp.TYPE_DATA, channel, flags, msgId, 0, body));
        }
        return packets;
    }

    /**
     * 出队发送：画面包优先（新鲜数据，且不受可靠窗口牵制），再填可靠窗口。
     * 调用方必须持锁。
     */
    private void pump() {
        while (!screenWaiting.isEmpty()) {
            sink.send(screenWaiting.poll());
        }
        while (unacked.size() < WINDOW && !reliableWaiting.isEmpty()) {
            byte[] packet = reliableWaiting.poll();
            long seq = nextSeq++;
            byte[] numbered = withSeq(packet, seq);
            unacked.addLast(new Sent(seq, numbered, System.currentTimeMillis()));
            sink.send(numbered);
        }
        notifyAll();
    }

    /** 改写报文头里的 seq 字段（第 8..11 字节），避免为了编号把消息重新分片一遍 */
    private static byte[] withSeq(byte[] packet, long seq) {
        byte[] copy = packet.clone();
        copy[8] = (byte) (seq >> 24);
        copy[9] = (byte) (seq >> 16);
        copy[10] = (byte) (seq >> 8);
        copy[11] = (byte) seq;
        return copy;
    }

    /** 控制通道报文（握手/心跳）直发，不进窗口不重传 */
    void sendControl(byte type, String text) {
        byte[] body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        sink.send(Dxp.encode(type, Dxp.CH_CONTROL, (byte) 0, 0, 0, body));
    }

    void sendControl(byte type, byte[] body) {
        sink.send(Dxp.encode(type, Dxp.CH_CONTROL, (byte) 0, 0, 0, body));
    }

    /* ==================== 接收 ==================== */

    /**
     * 收到一个报文。ACK / DATA 由本类消化并返回 true；
     * 其它类型（HELLO/PING/PONG/CLOSE）交上层识别，返回 false。
     */
    boolean onPacket(Dxp.Packet packet) {
        return switch (packet.type()) {
            case Dxp.TYPE_ACK -> {
                acknowledge(Dxp.ackSeq(packet.body()));
                yield true;
            }
            case Dxp.TYPE_DATA -> {
                yield deliver(packet);
            }
            default -> false;
        };
    }

    private void acknowledge(long highestContiguous) {
        List<byte[]> resend = null;
        synchronized (this) {
            if (highestContiguous < lastContiguous) {
                return;
            }
            while (!unacked.isEmpty() && unacked.peekFirst().seq() <= highestContiguous) {
                unacked.pollFirst();
            }
            lastContiguous = highestContiguous;
            rtoMs = (int) RTO_BASE_MS;
            timeoutStreak = 0;
            if (retransmitHint) {
                // 之前因拥堵拒过消息：现在排空了，通知上层可以重新用直连
                retransmitHint = false;
            }
            pump();
            resend = null;
        }
    }

    private boolean deliver(Dxp.Packet packet) {
        Dxp.Fragment fragment = Dxp.fragment(packet.body());
        if (fragment == null) {
            return true;
        }
        if (packet.channel() == Dxp.CH_SCREEN) {
            deliverScreen(packet, fragment);
            return true;
        }
        if (packet.channel() != Dxp.CH_RELIABLE) {
            return true;
        }
        byte kind;
        byte[] message;
        boolean complete;
        boolean sendAck;
        long ackValue;
        synchronized (this) {
            if (packet.seq() != expectedSeq) {
                // 乱序或重复：GBN 直接丢，并立刻回 ACK 触发对端整窗重发
                ackDue = true;
                return true;
            }
            expectedSeq++;
            complete = accept(reliableSlot, packet.msgId(), fragment);
            kind = (byte) reliableSlot.kind;
            message = reliableSlot.buffer.toByteArray();
            if (complete) {
                reliableSlot.reset();
            }
            ackValue = expectedSeq - 1;
            // 每条消息完成确认一次；长文件传输期间每 8 包确认一次，兼顾反馈及时与开销
            sendAck = complete || (expectedSeq & 7) == 0;
            if (!sendAck) {
                ackDue = true;
            }
        }
        if (sendAck) {
            sendControl(Dxp.TYPE_ACK, Dxp.ackBody(ackValue));
        }
        if (complete) {
            listener.onMessage(kind, message);
        }
        return true;
    }

    private void deliverScreen(Dxp.Packet packet, Dxp.Fragment fragment) {
        byte kind;
        byte[] message;
        boolean complete;
        synchronized (this) {
            Slot slot = screenSlot;
            if (slot.msgId != packet.msgId()) {
                slot.reset();
                slot.msgId = packet.msgId();
                if (fragment.index() != 0) {
                    // 从半路开始的消息永远拼不齐，整条作废等下一帧
                    slot.poisoned = true;
                }
            }
            if (slot.poisoned) {
                return;
            }
            complete = accept(slot, packet.msgId(), fragment);
            kind = (byte) slot.kind;
            message = slot.buffer.toByteArray();
            if (complete) {
                slot.reset();
            }
        }
        if (complete) {
            listener.onMessage(kind, message);
        }
    }

    /**
     * 顺序追加一个分片，返回消息是否已完整。要求 index 连续——可靠通道由 ARQ 保证有序，
     * 画面通道一旦跳号即整条作废。
     */
    private boolean accept(Slot slot, int msgId, Dxp.Fragment fragment) {
        slot.updatedAtMs = System.currentTimeMillis();
        if (slot.count == 0) {
            slot.count = fragment.count();
            slot.kind = fragment.kind();
        }
        if (fragment.index() != slot.expectedIndex || slot.count != fragment.count()) {
            slot.poisoned = true;
            return false;
        }
        slot.buffer.write(fragment.data(), 0, fragment.data().length);
        slot.expectedIndex++;
        return slot.expectedIndex >= slot.count;
    }

    /** 对端 seq 空间被拒过（拥堵），上层可用于判断是否需要回落 */
    boolean congested() {
        synchronized (this) {
            return reliableWaiting.size() + unacked.size() > RELIABLE_QUEUE;
        }
    }

    /* ==================== 定时驱动 ==================== */

    /** 由调度线程每 50ms 调一次：超时重传、补发 ACK、清理卡死的画面槽位 */
    void tick() {
        List<byte[]> resend = null;
        byte[] ack = null;
        boolean died = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (!unacked.isEmpty()) {
                Sent oldest = unacked.peekFirst();
                if (now - oldest.sentAtMs() > rtoMs) {
                    if (++timeoutStreak >= MAX_TIMEOUT_STREAK) {
                        healthy = false;
                        died = true;
                    }
                    rtoMs = (int) Math.min(rtoMs * 2L, RTO_MAX_MS);
                    List<Sent> current = new ArrayList<>(unacked);
                    unacked.clear();
                    resend = new ArrayList<>(current.size());
                    for (Sent sent : current) {
                        unacked.addLast(new Sent(sent.seq(), sent.packet(), now));
                        resend.add(sent.packet());
                    }
                }
            } else {
                rtoMs = (int) RTO_BASE_MS;
                timeoutStreak = 0;
            }
            if (ackDue) {
                ackDue = false;
                ack = Dxp.encode(Dxp.TYPE_ACK, Dxp.CH_CONTROL, (byte) 0, 0, 0,
                        Dxp.ackBody(expectedSeq - 1));
            }
            if (screenSlot.msgId >= 0 && now - screenSlot.updatedAtMs > SCREEN_SLOT_STALE_MS) {
                screenSlot.reset();
            }
            pump();
        }
        if (died) {
            // 让上层知道「别再发了」，避免继续往一个已经打不通的窗口里堆数据
            return;
        }
        if (ack != null) {
            sink.send(ack);
        }
        if (resend != null) {
            for (byte[] packet : resend) {
                sink.send(packet);
            }
        }
    }
}
