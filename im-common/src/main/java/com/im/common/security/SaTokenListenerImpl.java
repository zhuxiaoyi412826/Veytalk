package com.im.common.security;

import cn.dev33.satoken.listener.SaTokenListenerForSimple;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.im.common.spi.LoginAuditSpi;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.util.DeviceIds;
import com.im.common.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 全局事件监听器。
 *
 * <p>职责有二：
 * <ol>
 *   <li>记录登录、注销、踢下线、顶下线等安全审计日志，并把登录动作转交给 im-user 做设备登记与同端顶号；</li>
 *   <li>同步维护 Redis 在线状态（{@code im:online:{userId}}），
 *       使 im-user 的在线状态查询与 Sa-Token 会话生命周期保持一致。</li>
 * </ol>
 *
 * <p>在线键的 field 从 1.0.9 起是<b>设备位</b>（{@code 类型:实例}）而不是设备类型，与 WebSocket
 * 注册表、{@code im_user_device} 同源；老客户端只传类型码，它的设备位就等于类型码，因此
 * 存量语义不变。
 *
 * <p>继承 {@link SaTokenListenerForSimple} 只覆写关心的事件，避免实现全部抽象方法。
 */
@Slf4j
@Component
public class SaTokenListenerImpl extends SaTokenListenerForSimple {

    private final ObjectProvider<OnlineStatusSpi> onlineStatusProviders;
    /** 设备登记 / 顶号的实现在 im-user（它才有 mapper），本模块只认接口 */
    private final ObjectProvider<LoginAuditSpi> loginAuditProviders;

    public SaTokenListenerImpl(ObjectProvider<OnlineStatusSpi> onlineStatusProviders,
                               ObjectProvider<LoginAuditSpi> loginAuditProviders) {
        this.onlineStatusProviders = onlineStatusProviders;
        this.loginAuditProviders = loginAuditProviders;
    }

    @Override
    public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        String device = loginParameter == null ? null
                : DeviceIds.slot(loginParameter.getDeviceType(), loginParameter.getDeviceId());
        log.info("[登录] userId={}, device={}, token={}, ip={}",
                loginId, device, maskToken(tokenValue), SecurityUtil.getClientIp());
        Long userId = toUserId(loginId);
        OnlineStatusSpi spi = onlineStatusProviders.getIfAvailable();
        if (userId != null && spi != null) {
            spi.setOnline(userId, device == null || device.isBlank() ? SecurityUtil.getDevice() : device);
        }
        LoginAuditSpi audit = loginAuditProviders.getIfAvailable();
        if (audit != null) {
            // 设备登记与同端顶号放在监听器而不是各登录入口，是为了让账号密码 / 短信 / 邮箱 / 扫码
            // 四条路径都只有一份口径；漏一条就会出现「某种登录方式登进来，设备列表里查不到」。
            try {
                audit.onLogin(userId, tokenValue, device, SecurityUtil.getClientIp(),
                        SecurityUtil.getUserAgent(), SecurityUtil.getDeviceName());
            } catch (Exception e) {
                // 审计失败不能把登录本身带倒：宁可少一条设备记录，也不能让用户登不进去
                log.warn("[登录] 设备登记失败（不影响本次登录）: userId={}, err={}", loginId, e.getMessage());
            }
        }
    }

    @Override
    public void doLogout(String loginType, Object loginId, String tokenValue) {
        log.info("[注销] userId={}, device={}, token={}", loginId, resolveDevice(tokenValue), maskToken(tokenValue));
        offline(loginId, tokenValue);
    }

    @Override
    public void doKickout(String loginType, Object loginId, String tokenValue) {
        log.info("[踢下线] userId={}, device={}, token={}", loginId, resolveDevice(tokenValue), maskToken(tokenValue));
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
     * 从 token 反查登录设备位；会话已被清理时返回 {@code null}。
     *
     * <p>必须把类型和实例拼回成设备位再去下在线键：只拿类型码的话，同一类设备的两台机器
     * 会共用一个 field，踢掉其中一台会把另一台的在线状态一起抹掉（表现为「明明在线却不绿」）。
     */
    private String resolveDevice(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return null;
        }
        try {
            String type = StpUtil.getLoginDeviceTypeByToken(tokenValue);
            if (type == null || type.isBlank()) {
                return null;
            }
            return DeviceIds.slot(type, StpUtil.getLoginDeviceIdByToken(tokenValue));
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
