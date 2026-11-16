package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.ClientRelease;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户端安装包版本表映射。表归 im-admin 所有（与 im_announcement 同例：
 * 系统级管理表不跨模块设 SPI），本接口是全站唯一映射它的地方。
 */
@Mapper
public interface ClientReleaseMapper extends BaseMapper<ClientRelease> {
}
