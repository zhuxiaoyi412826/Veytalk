package com.im.user.spi;

import com.im.common.constant.RedisKeys;
import com.im.common.spi.PermissionProvider;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.user.mapper.PermissionMapper;
import com.im.user.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限数据源实现。
 *
 * <p>common 模块的 {@code StpInterfaceImpl} 通过 {@code ObjectProvider} 委派到这里，
 * 因此 im-common 无需依赖 im-user 即可支撑 {@code @SaCheckRole} / {@code @SaCheckPermission}。
 *
 * <p>鉴权在每次请求都可能触发，联表查询代价不低，故结果按用户缓存 30 分钟；
 * 角色或权限发生变更时由业务方调用 {@link #evictCache} 主动失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionProviderImpl implements PermissionProvider {

    /** 权限缓存有效期 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = toUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = RedisKeys.authPermission(userId);
        List<String> cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        List<String> codes = permissionMapper.selectPermCodesByUserId(userId);
        if (codes == null) {
            codes = Collections.emptyList();
        }
        writeCache(key, codes);
        return codes;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = toUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        String key = RedisKeys.authRole(userId);
        List<String> cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        List<String> codes = roleMapper.selectRoleCodesByUserId(userId);
        if (codes == null) {
            codes = Collections.emptyList();
        }
        writeCache(key, codes);
        return codes;
    }

    @Override
    public void evictCache(Long userId) {
        if (userId == null) {
            return;
        }
        redisUtil.delete(List.of(RedisKeys.authPermission(userId), RedisKeys.authRole(userId)));
        log.debug("已清除用户 {} 的角色权限缓存", userId);
    }

    /**
     * 命中缓存返回列表（含空列表），未命中返回 {@code null} 以便回源。
     */
    private List<String> readCache(String key) {
        String json = redisUtil.get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        return jsonUtil.toList(json, String.class);
    }

    private void writeCache(String key, List<String> codes) {
        String json = jsonUtil.toJson(codes);
        if (json != null) {
            redisUtil.set(key, json, CACHE_TTL);
        }
    }

    private Long toUserId(Object loginId) {
        try {
            return Long.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException e) {
            log.warn("无法解析登录 ID: {}", loginId);
            return null;
        }
    }
}
