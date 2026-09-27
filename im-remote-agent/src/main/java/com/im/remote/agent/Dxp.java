package com.im.remote.agent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 直连协议 DXP（Direct eXchange Protocol）报文编解码——纯 JDK，零依赖。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>中继通道的成帧是 WebSocket 给的；直连有两档通道，一档是 Agent 手搓的
 * WebSocket 服务端（局域网浏览器/Electron 可用），另一档是 UDP（只有 Electron 主进程
 * 能开原始 socket，且必须自己解决分片与重传）。两档共用同一份「报文」抽象，
 * 才能把上层的帧语义、鉴权、统计只写一遍。
 *
 * <h2>报文格式（大端）</h2>
 *
 * <pre>
 *  0  u8   ver = 1
 *  1  u8   type   1=HELLO 2=HELLO_ACK 3=DATA 4=ACK 6=PING 7=PONG 8=CLOSE
 *  2  u8   channel 0=控制(握手/心跳，不参与 ARQ) 1=画面(不可靠) 2=可靠(指令+信封+文件)
 *  3  u8   flags  bit0=该报文的最后一个分片
 *  4  u32  msgId  消息序号（分片归属，发送端全局递增）
 *  8  u32  seq    包序号（仅可靠通道有意义；画面/控制通道恒为 0）
 * 12  ...  body
 * </pre>
 *
 * <p>DATA 的 body 再带 6 字节：{@code [u16 fragIdx][u16 fragCnt][u8 kind][u8 保留]}，
 * kind 0=文本信封（JSON UTF-8）1=二进制帧（{@code RemoteFrame} 的 13B 头 + meta + 载荷），
 * 与中继通道的「WS 文本帧 / WS 二进制帧」一一对应，因此两端已有的帧解析代码可以原样复用。
 * 单分片可用载荷 = mtu - 18，mtu 默认 1200（加 UDP/IP 头正好一个以太网帧，避开路由器分片）。
 *
 * <p>TCP/WebSocket 档不做分片：握手时协商 mtu=1MB，fragCnt 恒为 1，
 * 一条 WS 消息正好一个 DXP 报文——同一段解析代码，两种传输都能跑。
 */
public final class Dxp {

    public static final byte VER = 1;
    /** 固定头长度 */
    public static final int HEADER = 12;
    /** DATA body 自带的分片头长度 */
    public static final int DATA_HEADER = 6;
    /** 单包总长上限的兜底值（协商值大于它时按它执行），防止恶意超大包撑爆内存 */
    public static final int MAX_MTU = 1_048_576;

    public static final byte TYPE_HELLO = 1;
    public static final byte TYPE_HELLO_ACK = 2;
    public static final byte TYPE_DATA = 3;
    public static final byte TYPE_ACK = 4;
    public static final byte TYPE_PING = 6;
    public static final byte TYPE_PONG = 7;
    public static final byte TYPE_CLOSE = 8;

    public static final byte CH_CONTROL = 0;
    public static final byte CH_SCREEN = 1;
    public static final byte CH_RELIABLE = 2;

    public static final byte KIND_TEXT = 0;
    public static final byte KIND_BINARY = 1;

    public static final byte FLAG_LAST = 1;

    private Dxp() {
    }

    /** 一个已编码或待编码的报文；body 对 DATA 来说是「6B 分片头 + 数据」 */
    public record Packet(byte type, byte channel, byte flags, int msgId, long seq, byte[] body) {
    }

    /** DATA 报文的分片视图 */
    public record Fragment(int index, int count, byte kind, byte[] data) {
    }

    public static byte[] encode(byte type, byte channel, byte flags, int msgId, long seq, byte[] body) {
        int length = body == null ? 0 : body.length;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER + length);
        buffer.put(VER);
        buffer.put(type);
        buffer.put(channel);
        buffer.put(flags);
        buffer.putInt(msgId);
        buffer.putInt((int) (seq & 0xFFFF_FFFFL));
        if (length > 0) {
            buffer.put(body);
        }
        return buffer.array();
    }

    /**
     * 解一个报文。刻意宽松：长度不够或版本不符返回 null 由调用方丢弃，
     * 不抛异常——UDP 上畸形包是常态（打洞期会收到对端还没握手时的杂包），
     * 丢包是数据面行为，不该冒到回调线程变成日志洪水。
     */
    public static Packet decode(byte[] raw, int length) {
        if (raw == null || length < HEADER || raw[0] != VER) {
            return null;
        }
        int bodyLen = length - HEADER;
        byte[] body = new byte[bodyLen];
        System.arraycopy(raw, HEADER, body, 0, bodyLen);
        return new Packet(raw[1], raw[2], raw[3],
                ByteBuffer.wrap(raw, 4, 4).getInt(),
                ByteBuffer.wrap(raw, 8, 4).getInt() & 0xFFFF_FFFFL, body);
    }

    /* ==================== DATA 分片头 ==================== */

    public static byte[] fragmentBody(int index, int count, byte kind, byte[] data, int offset, int len) {
        ByteBuffer buffer = ByteBuffer.allocate(DATA_HEADER + len);
        buffer.putShort((short) index);
        buffer.putShort((short) count);
        buffer.put(kind);
        buffer.put((byte) 0);
        buffer.put(data, offset, len);
        return buffer.array();
    }

    public static Fragment fragment(byte[] body) {
        if (body == null || body.length < DATA_HEADER) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(body);
        int index = buffer.getShort() & 0xFFFF;
        int count = buffer.getShort() & 0xFFFF;
        byte kind = buffer.get();
        buffer.get();
        byte[] data = new byte[body.length - DATA_HEADER];
        buffer.get(data);
        return new Fragment(index, count, kind, data);
    }

    /** 单分片可携带的载荷字节数 */
    public static int payloadCapacity(int mtu) {
        return Math.max(64, Math.min(mtu, MAX_MTU) - HEADER - DATA_HEADER);
    }

    public static int fragmentCount(int mtu, int messageLength) {
        int capacity = payloadCapacity(mtu);
        return Math.max(1, (messageLength + capacity - 1) / capacity);
    }

    /* ==================== ACK 体 ==================== */

    public static byte[] ackBody(long lastContiguousSeq) {
        return ByteBuffer.allocate(4).putInt((int) (lastContiguousSeq & 0xFFFF_FFFFL)).array();
    }

    public static long ackSeq(byte[] body) {
        if (body == null || body.length < 4) {
            return -1;
        }
        return ByteBuffer.wrap(body).getInt() & 0xFFFF_FFFFL;
    }

    /* ==================== 握手（明文 JSON，字段见 DirectChannel 注释） ==================== */

    /** HELLO/HELLO_ACK 用一个字节前缀区分角色就够，其余交给 MiniJson */
    public static byte[] helloBody(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static String helloJson(byte[] body) {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    /** 该类型是否需要可靠通道（只有 DATA 的可靠通道参与 ARQ） */
    public static boolean controlType(byte type) {
        return type != TYPE_DATA;
    }
}
