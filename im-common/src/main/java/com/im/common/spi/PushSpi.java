package com.im.common.spi;

import com.im.common.domain.WsPacket;

import java.util.Collection;

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
     * @param deviceId 为空表示踢掉全部设备
     * @param reason   下线原因，展示给客户端
     */
    void kickOut(Long userId, String deviceId, String reason);

    /**
     * 用户是否当前存在活跃的 WebSocket 连接。
     */
    boolean isUserOnline(Long userId);
}
