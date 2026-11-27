package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.UserDevice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录设备 Mapper。
 *
 * <p>本表的所有读写都在 {@code DeviceService} 里，且几乎全是「按 user_id 取那一台或那一类」，
 * 用 {@code BaseMapper} 加 LambdaQuery 就够，不需要任何手写 SQL——唯一键
 * {@code uk_user_device} 负责「一台机器一行」，服务层只管按 (userId, deviceType, deviceId) 查一下
 * 再决定 INSERT 还是 UPDATE。
 */
@Mapper
public interface UserDeviceMapper extends BaseMapper<UserDevice> {
}
