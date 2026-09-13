package com.im.common.spi;

import java.util.List;

/**
 * Sa-Token 权限数据源契约，由 im-user 模块实现。
 *
 * <p>common 模块的 {@code StpInterfaceImpl} 通过 {@code ObjectProvider} 委派到此接口，
 * 从而使权限数据的存储细节留在 user 模块内部。
 */
public interface PermissionProvider {

    /**
     * 返回账号的权限码集合。
     *
     * @param loginId   登录 ID
     * @param loginType 登录体系
     */
    List<String> getPermissionList(Object loginId, String loginType);

    /**
     * 返回账号的角色码集合。
     *
     * @param loginId   登录 ID
     * @param loginType 登录体系
     */
    List<String> getRoleList(Object loginId, String loginType);

    /**
     * 清除指定账号的权限缓存，角色变更时调用。
     */
    void evictCache(Long userId);
}
