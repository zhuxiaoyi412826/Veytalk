package com.im.common.security;

import cn.dev33.satoken.stp.StpInterface;
import com.im.common.spi.PermissionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源适配器。
 *
 * <p>im-common 不感知用户表结构，实际的权限/角色查询由 im-user 模块提供
 * {@link PermissionProvider} 实现；这里通过 {@link ObjectProvider} 延迟获取，
 * 既避免了 common 对 user 的编译期依赖，也允许在未装配 user 模块的场景下安全降级为空权限。
 */
@Slf4j
@Component
public class StpInterfaceImpl implements StpInterface {

    private final ObjectProvider<PermissionProvider> permissionProviders;

    public StpInterfaceImpl(ObjectProvider<PermissionProvider> permissionProviders) {
        this.permissionProviders = permissionProviders;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        PermissionProvider provider = permissionProviders.getIfAvailable();
        if (provider == null) {
            log.warn("未装配 PermissionProvider 实现，用户 {} 权限列表返回空", loginId);
            return List.of();
        }
        return provider.getPermissionList(loginId, loginType);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        PermissionProvider provider = permissionProviders.getIfAvailable();
        if (provider == null) {
            log.warn("未装配 PermissionProvider 实现，用户 {} 角色列表返回空", loginId);
            return List.of();
        }
        return provider.getRoleList(loginId, loginType);
    }
}
