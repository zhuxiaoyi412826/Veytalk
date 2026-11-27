package com.im.common.spi;

/**
 * 登录审计契约，由 im-user 模块实现（设备登记 + 同端顶号 + 登录流水）。
 *
 * <p>放在 im-common 是因为唯一的触发点是 Sa-Token 的全局监听器（也在 im-common），
 * 而真正实现需要 mapper 和 WebSocket 推送，只能在业务模块。与 {@link OnlineStatusSpi} 同一套解耦方式：
 * 监听器只认接口，装配不上时静默跳过——交友、钱包那些可选模块就是这么共存的。
 */
public interface LoginAuditSpi {

    /**
     * 一次登录成功后的登记与超额处理。
     *
     * <p>实现方要负责两件事，且顺序不能反：① 把这台设备写进设备表并记一条登录流水；
     * ② 按「同端最多 N 台」把超额的<b>其它</b>设备顶下线（先推 WebSocket 报文再注销会话）。
     * 本方法在 Sa-Token 的 {@code doLogin} 回调里执行，此时新会话已经落进 Redis，
     * 所以实现里可以安全地重新读取账号会话做淘汰。
     *
     * <p>实现不得抛出异常打断登录：调用方（监听器）虽然包了 try-catch，但语义上这里失败
     * 只该少一条记录，不该让用户登不进去。
     *
     * @param userId     登录用户
     * @param tokenValue 本次登录签发的 token，用于把「自己」从待淘汰名单里排除
     * @param deviceSlot 设备位（{@code 类型:实例}），老客户端为裸类型码
     * @param ip         客户端 IP
     * @param userAgent  User-Agent（已截断），用于兜底推设备名
     * @param deviceName 客户端自报的设备友好名，可为空
     */
    void onLogin(Long userId, String tokenValue, String deviceSlot, String ip, String userAgent, String deviceName);
}
