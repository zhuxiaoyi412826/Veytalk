package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色 Mapper，额外提供「按用户查角色码」与「按角色码查主键」两个联表查询。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 查询用户持有的全部启用角色编码。
     */
    @Select("""
            SELECT r.role_code
            FROM im_user_role ur
                     JOIN im_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
              AND r.deleted = 0
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 按角色编码查主键，注册时绑定默认角色使用。
     */
    @Select("""
            SELECT r.id
            FROM im_role r
            WHERE r.role_code = #{roleCode}
              AND r.deleted = 0
            LIMIT 1
            """)
    Long selectIdByRoleCode(@Param("roleCode") String roleCode);
}
