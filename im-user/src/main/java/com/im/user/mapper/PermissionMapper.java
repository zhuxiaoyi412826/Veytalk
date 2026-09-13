package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限 Mapper，提供 Sa-Token 鉴权所需的「用户 -> 权限码」联表查询。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查询用户经由角色间接持有的全部启用权限码，去重返回。
     */
    @Select("""
            SELECT DISTINCT p.perm_code
            FROM im_user_role ur
                     JOIN im_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
                     JOIN im_role_permission rp ON rp.role_id = r.id
                     JOIN im_permission p ON p.id = rp.permission_id AND p.status = 1 AND p.deleted = 0
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
