package com.im.common.spi;

import java.util.Collection;
import java.util.List;

/**
 * 在线状态契约，由 im-user 模块实现（数据落在 Redis）。
 */
public interface OnlineStatusSpi {

    /**
     * 标记某设备上线并刷新心跳时间。
     */
    void setOnline(Long userId, String deviceId);

    /**
     * 刷新心跳时间，不改变上下线状态。
     */
    void heartbeat(Long userId, String deviceId);

    /**
     * 标记某设备下线；该用户所有设备均下线时清除在线键。
     */
    void setOffline(Long userId, String deviceId);

    /**
     * 清除用户全部在线状态。
     */
    void clear(Long userId);

    /**
     * 查询用户当前在线的设备列表。
     */
    List<String> getOnlineDevices(Long userId);

    /**
     * 从给定集合中筛选出在线的用户 ID。
     */
    List<Long> filterOnline(Collection<Long> userIds);
}
