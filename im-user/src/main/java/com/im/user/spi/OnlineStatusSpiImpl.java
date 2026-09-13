package com.im.user.spi;

import com.im.common.enums.DeviceType;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.util.RedisUtil;
import com.im.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 在线状态实现，数据全部落在 Redis。
 *
 * <p>键结构：{@code im:online:{userId}} 为 Hash，field 是设备标识、value 是最近一次心跳的毫秒时间戳，
 * 整个键带 90 秒 TTL。只要任一设备在心跳，键就持续续期；读取时再按时间戳过滤掉已经静默的设备，
 * 这样即便多端共用一个键，也不会出现「某端早已断开却仍显示在线」的脏数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineStatusSpiImpl implements OnlineStatusSpi {

    /** 在线键 TTL，需大于客户端心跳周期（30s）的 2~3 倍 */
    private static final Duration ONLINE_TTL = Duration.ofSeconds(90);

    private final RedisUtil redisUtil;

    @Override
    public void setOnline(Long userId, String deviceId) {
        if (userId == null) {
            return;
        }
        String key = RedisKeys.online(userId);
        redisUtil.hSet(key, normalize(deviceId), String.valueOf(System.currentTimeMillis()));
        redisUtil.expire(key, ONLINE_TTL);
    }

    @Override
    public void heartbeat(Long userId, String deviceId) {
        if (userId == null) {
            return;
        }
        String key = RedisKeys.online(userId);
        String device = normalize(deviceId);
        // 键已过期说明用户其实已离线，此时心跳等同于重新上线
        redisUtil.hSet(key, device, String.valueOf(System.currentTimeMillis()));
        redisUtil.expire(key, ONLINE_TTL);
    }

    @Override
    public void setOffline(Long userId, String deviceId) {
        if (userId == null) {
            return;
        }
        String key = RedisKeys.online(userId);
        redisUtil.hDelete(key, normalize(deviceId));
        if (redisUtil.hGetAll(key).isEmpty()) {
            redisUtil.delete(key);
        }
    }

    @Override
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        redisUtil.delete(RedisKeys.online(userId));
    }

    @Override
    public List<String> getOnlineDevices(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Map<Object, Object> entries = redisUtil.hGetAll(RedisKeys.online(userId));
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        long deadline = System.currentTimeMillis() - ONLINE_TTL.toMillis();
        List<String> devices = new ArrayList<>(entries.size());
        entries.forEach((device, ts) -> {
            if (isAlive(ts, deadline)) {
                devices.add(String.valueOf(device));
            }
        });
        return devices;
    }

    @Override
    public List<Long> filterOnline(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> online = new ArrayList<>();
        for (Long userId : userIds) {
            if (userId != null && !getOnlineDevices(userId).isEmpty()) {
                online.add(userId);
            }
        }
        return online;
    }

    /**
     * 心跳时间戳晚于截止时间才认为该设备仍然在线。
     */
    private boolean isAlive(Object timestamp, long deadline) {
        try {
            return Long.parseLong(String.valueOf(timestamp)) >= deadline;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String normalize(String deviceId) {
        return DeviceType.codeOf(deviceId);
    }
}
