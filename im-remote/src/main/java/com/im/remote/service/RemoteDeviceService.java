package com.im.remote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.remote.entity.RemoteDevice;
import com.im.remote.mapper.RemoteDeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 设备登记：Agent 上下线时的 im_remote_device 行维护。
 *
 * <p>upsert 按 (userId, deviceId) 定位：同一台机器换账号登录会留下另一用户的行，
 * 设备列表天然按当前用户过滤，不做跨用户迁移——设备的「归属」就是第一个注册它的账号。
 */
@Service
@RequiredArgsConstructor
public class RemoteDeviceService {

    private final RemoteDeviceMapper deviceMapper;

    /** Agent auth 后登记/刷新设备，返回行（含主键）；accessCode 可为 null（只走同账号路径的设备） */
    public RemoteDevice upsert(Long userId, String deviceId, String deviceName, String os, String accessCode, int status) {
        RemoteDevice existing = find(userId, deviceId);
        if (existing == null) {
            RemoteDevice device = RemoteDevice.builder()
                    .userId(userId)
                    .deviceId(deviceId)
                    .deviceName(deviceName)
                    .os(os)
                    .accessCode(accessCode)
                    .status(status)
                    .lastOnlineTime(java.time.LocalDateTime.now())
                    .build();
            deviceMapper.insert(device);
            return device;
        }
        RemoteDevice patch = RemoteDevice.builder()
                .id(existing.getId())
                .deviceName(deviceName)
                .os(os)
                .accessCode(accessCode)
                .status(status)
                .lastOnlineTime(java.time.LocalDateTime.now())
                .build();
        deviceMapper.updateById(patch);
        return deviceMapper.selectById(existing.getId());
    }

    public RemoteDevice find(Long userId, String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<RemoteDevice>()
                .eq(RemoteDevice::getUserId, userId)
                .eq(RemoteDevice::getDeviceId, deviceId));
    }

    public List<RemoteDevice> listByUser(Long userId) {
        return deviceMapper.selectList(new LambdaQueryWrapper<RemoteDevice>()
                .eq(RemoteDevice::getUserId, userId)
                .orderByDesc(RemoteDevice::getLastOnlineTime));
    }

    /**
     * 批量按设备标识取设备行，供会话历史列表补设备名。
     *
     * <p>只按 deviceId 过滤、不拼 userId 条件：会话里的设备归属被控方，而列表是
     * 「我作为控制方或被控方」的混合视角，调用方拿到全量行后自己按 (userId, deviceId) 配对。
     * 同一台机器换账号登录会留下多行，配对时以归属人为准。
     */
    public List<RemoteDevice> listByDeviceIds(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return deviceMapper.selectList(new LambdaQueryWrapper<RemoteDevice>()
                .in(RemoteDevice::getDeviceId, deviceIds));
    }

    public void updateStatus(Long deviceId, int status) {
        RemoteDevice patch = RemoteDevice.builder().id(deviceId).status(status).build();
        deviceMapper.updateById(patch);
    }

    public RemoteDevice requireById(Long id) {
        return deviceMapper.selectById(id);
    }
}
