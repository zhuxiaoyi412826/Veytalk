package com.im.remote.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 二进制帧编解码：{@code [1B 帧类型][8B 会话ID][4B 元数据长度][元数据JSON][载荷]}。
 *
 * <p>刻意不做对象池、不抽接口——转发路径上 decode 只为取路由键与统计，
 * 中继对两端是「透明字节」语义，re-encode 直接拿原始 buffer 发送即可，
 * 因此这里提供的是「解析头部 + 保留原始视图」而不是完全反序列化。
 */
public record RemoteFrame(byte frameType, long sessionId, String meta, byte[] payload, byte[] raw) {

    /** 头部固定部分：1B 类型 + 8B 会话 + 4B 元数据长度 */
    private static final int FIXED_HEADER = 13;

    public static byte[] encode(byte frameType, long sessionId, String meta, byte[] payload) {
        byte[] metaBytes = (meta == null || meta.isEmpty())
                ? new byte[0] : meta.getBytes(StandardCharsets.UTF_8);
        int payloadLen = payload == null ? 0 : payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(FIXED_HEADER + metaBytes.length + payloadLen);
        buffer.put(frameType);
        buffer.putLong(sessionId);
        buffer.putInt(metaBytes.length);
        buffer.put(metaBytes);
        if (payloadLen > 0) {
            buffer.put(payload);
        }
        return buffer.array();
    }

    /**
     * 解析一帧。原始字节整体保留在 {@link #raw}，中继转发时直接发送它，
     * 避免任何「解析-重组」引入的语义漂移。
     *
     * @throws IllegalArgumentException 长度非法 / 元数据段越界（畸形帧，调用方应关闭连接）
     */
    public static RemoteFrame decode(byte[] raw, int maxMetaBytes) {
        if (raw == null || raw.length < FIXED_HEADER) {
            throw new IllegalArgumentException("binary frame too short: " + (raw == null ? 0 : raw.length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte frameType = buffer.get();
        long sessionId = buffer.getLong();
        int metaLen = buffer.getInt();
        if (metaLen < 0 || metaLen > maxMetaBytes || FIXED_HEADER + metaLen > raw.length) {
            throw new IllegalArgumentException("binary frame meta out of bound: metaLen=" + metaLen);
        }
        String meta;
        if (metaLen == 0) {
            meta = "";
        } else {
            byte[] metaBytes = new byte[metaLen];
            buffer.get(metaBytes);
            meta = new String(metaBytes, StandardCharsets.UTF_8);
        }
        byte[] payload = Arrays.copyOfRange(raw, FIXED_HEADER + metaLen, raw.length);
        return new RemoteFrame(frameType, sessionId, meta, payload, raw);
    }
}
