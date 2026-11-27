package com.im.user.spi;

import com.im.common.spi.LoginAuditSpi;
import com.im.user.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link LoginAuditSpi} 的实现：把 Sa-Token 监听器递过来的登录事件转给 {@link DeviceService}。
 *
 * <p>这一层薄得只有一句转调，但它决定了依赖方向：监听器在 im-common，而设备登记要查库、
 * 要推 WebSocket，只能在业务模块。im-common 因此不必反向依赖 im-user（后者本来就依赖前者）。
 *
 * <p>★ 这里<b>不</b>包 try-catch：唯一调用方 {@code SaTokenListenerImpl.doLogin} 已经包了，
 * 两处都吞就成了「设备登记静默失败、日志里也查不到为什么」。异常往上抛正是为了让它被记一次 warn。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAuditSpiImpl implements LoginAuditSpi {

    private final DeviceService deviceService;

    @Override
    public void onLogin(Long userId, String tokenValue, String deviceSlot, String ip,
                        String userAgent, String deviceName) {
        deviceService.onLogin(userId, tokenValue, deviceSlot, ip, userAgent, deviceName);
    }
}
