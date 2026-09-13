package com.im.common.security;

import cn.dev33.satoken.listener.SaTokenListenerForSimple;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 全局事件监听器。
 *
 * <p>职责有二：
 * <ol>
 *   <li>记录登录、注销、踢下线、顶下线等安全审计日志；</li>
 *   <li>同步维护 Redis 在线状态（{@code im:online:{userId}}），
 *       使 im-user 的在线状态查询与 Sa-Token 会话生命周期保持一致。</li>
 * </ol>
 *
 * <p>继承 {@link SaTokenListenerForSimple} 只覆写关心的事件，避免实现全部抽象方法。
 */
@Slf4j
@Component
public class SaTokenListenerImpl extends SaTokenListenerForSimple {

    private final ObjectProvider<OnlineStatusSpi> onlineStatusProviders;

    public SaTokenListenerImpl(ObjectProvider<OnlineStatusSpi> onlineStatusProviders) {
        this.onlineStatusProviders = onlineStatusProviders;
    }

    @Override
    public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        String device = loginParameter == null ? null : loginParameter.getDeviceType();
        log.info("[登录] userId={}, device={}, token={}, ip={}",
                loginId, device, maskToken(tokenValue), SecurityUtil.getClientIp());
        Long userId = toUserId(loginId);
        OnlineStatusSpi spi = onlineStatusProviders.getIfAvailable();
        if (userId != null && spi != null) {
            spi.setOnline(userId, device == null || device.isBlank() ? SecurityUtil.getDevice() : device);
        }
    }

    @Override
    public void doLogout(String loginType, Object loginId, String tokenValue) {
        log.info("[注销] userId={}, device={}, token={}", loginId, resolveDevice(tokenValue), maskToken(tokenValue));
        offline(loginId, tokenValue);
    }

    @Override
    public void doKickout(String loginType, Object loginId, String tokenValue) {
        log.info("[踢下线] userId={}, token={}", loginId, maskToken(tokenValue));
        offline(loginId, tokenValue);
    }

    @Override
    public void doReplaced(String loginType, Object loginId, String tokenValue) {
        log.info("[顶下线] userId={}, token={}", loginId, maskToken(tokenValue));
        offline(loginId, tokenValue);
    }

    @Override
    public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {
        log.warn("[封禁] userId={}, service={}, level={}, 时长={}s", loginId, service, level, disableTime);
    }

    @Override
    public void doUntieDisable(String loginType, Object loginId, String service) {
        log.info("[解封] userId={}, service={}", loginId, service);
    }

    /**
     * 下线时清理在线状态。能解析到设备就只下掉该设备，解析不到时保守地清理全部终端。
     *
     * <p>多端共存时这一点很关键：在浏览器上注销不应该让手机端的在线状态一并消失。
     */
    private void offline(Object loginId, String tokenValue) {
        Long userId = toUserId(loginId);
        OnlineStatusSpi spi = onlineStatusProviders.getIfAvailable();
        if (userId == null || spi == null) {
            return;
        }
        String device = resolveDevice(tokenValue);
        if (device == null) {
            spi.clear(userId);
        } else {
            spi.setOffline(userId, device);
        }
    }

    /**
     * 从 token 反查登录设备；会话已被清理时返回 {@code null}。
     */
    private String resolveDevice(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return null;
        }
        try {
            String device = StpUtil.getLoginDeviceTypeByToken(tokenValue);
            return device == null || device.isBlank() ? null : device;
        } catch (Exception e) {
            return null;
        }
    }

    private Long toUserId(Object loginId) {
        try {
            return Long.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** token 只保留首尾，避免完整凭证落盘 */
    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
