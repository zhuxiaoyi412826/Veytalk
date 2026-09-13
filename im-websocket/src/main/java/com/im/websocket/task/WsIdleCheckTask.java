package com.im.websocket.task;

import com.im.common.config.ImProperties;
import com.im.websocket.manager.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 空闲连接回收任务。
 *
 * <p>不能只依赖 TCP 自身来发现死连接：浏览器进程被杀、手机切到后台、Wi-Fi 直接断掉，
 * 这几种情况下都不会有 FIN 包发出，服务端的 socket 会一直停在 ESTABLISHED。
 * 这些半开连接既占着文件描述符，又挂在注册表里让 {@code isUserOnline} 返回 true，
 * 于是消息被推进一个黑洞，而好友列表还显示他在线。
 *
 * <p>心跳超时扫描是唯一可靠的回收手段。{@code WsIdleCheckTask} 与容器级的
 * {@code maxSessionIdleTimeout} 是两道互补的防线：容器那道只管关 socket，
 * 而注册表里的 {@code WsConnection} 和 Redis 里的在线键它并不知情，
 * 只有走这条路径才能把三处状态一起清干净。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsIdleCheckTask {

    private final WsSessionManager sessionManager;
    private final ImProperties imProperties;

    /**
     * 扫描并关闭心跳超时的连接。
     *
     * <p>调度间隔从配置项直接读取，默认值必须与 {@code ImProperties.Websocket#idleCheckIntervalMs}
     * 保持一致——注解上的占位符走 Spring Environment，拿不到那个字段的默认值，
     * 两处不一致的话，yml 里没配这一项时实际生效的是这里的 30 秒。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者从上一次执行结束开始计时，
     * 扫描本身慢下来时不会堆积任务。连接数上到几万时一次全量扫描可能要几秒，
     * {@code fixedRate} 会让下一次扫描在上一次还没结束时就排队进来。
     */
    @Scheduled(fixedDelayString = "${im.websocket.idle-check-interval-ms:30000}")
    public void evictIdleConnections() {
        long timeoutMillis = imProperties.getWebsocket().getHeartbeatTimeoutSeconds() * 1000L;
        int closed = sessionManager.closeIdle(timeoutMillis);
        if (closed > 0) {
            // 没有回收任何连接时不打日志：这个任务每 30 秒跑一次，常态输出会淹没真正有用的信息
            log.info("WebSocket 空闲连接回收: 关闭={}, 剩余在线用户={}, 剩余连接={}",
                    closed, sessionManager.onlineUserCount(), sessionManager.connectionCount());
        }
    }
}
