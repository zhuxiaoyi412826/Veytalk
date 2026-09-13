package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.UserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色关联 Mapper。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
}
