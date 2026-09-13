package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 *
 * <p>由 im-bootstrap 的 {@code @MapperScan("com.im.**.mapper")} 统一扫描注册。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
