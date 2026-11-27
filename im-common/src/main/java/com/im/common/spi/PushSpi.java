package com.im.common.spi;

import com.im.common.domain.WsPacket;

import java.util.Collection;
import java.util.Map;

/**
 * 实时推送契约，由 im-websocket 模块实现。
 *
 * <p>其他模块只依赖此接口即可推送消息，无需感知 WebSocket 会话细节；
 * 目标用户不在线时推送静默失败，消息本身已持久化，上线后由离线拉取补齐。
 */
public interface PushSpi {

    /**
     * 推送给某用户的全部在线端。
     *
     * @return 至少推送成功一个连接时返回 {@code true}
     */
    boolean pushToUser(Long userId, WsPacket packet);

    /**
     * 推送给某用户的指定设备。
     *
     * @param deviceId 为空表示推送全部设备
     */
    boolean pushToDevice(Long userId, String deviceId, WsPacket packet);

    /**
     * 批量推送给多个用户，内部按用户分片，忽略不在线者。
     *
     * @param excludeUserId 需要排除的用户（通常是发送者自己），可为空
     */
    void pushToUsers(Collection<Long> userIds, Long excludeUserId, WsPacket packet);

    /**
     * 广播给全部在线连接，仅系统级公告使用。
     */
    void broadcast(WsPacket packet);

    /**
     * 强制下线：先推送 kickout 报文再关闭连接。
     *
     * @param deviceId 为空表示踢掉全部设备；从 1.0.9 起这里传的是<b>设备位</b>（{@code 类型:实例}），
     *                 与 WebSocket 注册表的键同源
     * @param reason   下线原因，展示给客户端
     */
    void kickOut(Long userId, String deviceId, String reason);

    /**
     * 带附加信息的强制下线。
     *
     * <p>顶号与封号要给用户看的不是一个「原因」就完事：QQ 式被踢时用户需要知道
     * 「设备：Pixel 8 · IP：192.168.1.20 · 时间：11-27 14:03」才能判断那是不是自己人。
     * 这些信息只有调用方（它才知道是谁把谁挤了）拿得到，所以下沉为一个可选的 detail 而不是另开接口。
     *
     * <p>默认实现直接丢弃 detail 转调三参版本，因此现有实现不强制改；
     * 需要把字段送进报文的实现（im-websocket）覆写它。
     *
     * @param detail 合并进报文 {@code data} 的额外键（如 {@code deviceName} / {@code ip} / {@code time}），可为空
     */
    default void kickOut(Long userId, String deviceId, String reason, Map<String, Object> detail) {
        kickOut(userId, deviceId, reason);
    }

    /**
     * 用户是否当前存在活跃的 WebSocket 连接。
     */
    boolean isUserOnline(Long userId);
}
