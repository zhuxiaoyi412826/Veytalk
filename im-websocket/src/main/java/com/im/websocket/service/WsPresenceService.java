package com.im.websocket.service;

import com.im.common.config.ImProperties;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.WsMessageType;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.OnlineStatusSpi;
import com.im.websocket.manager.WsConnection;
import com.im.websocket.manager.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接的在线状态编排：把「WebSocket 连上了/断了」翻译成全系统一致的在线语义。
 *
 * <p>拆出这个类而不是把逻辑塞进 {@code ImWebSocketHandler}，是因为 handler 只该关心
 * 「容器给了我一个帧」，而一个帧背后要不要刷 Redis、要不要补推离线消息、失败了要不要断连，
 * 是另一层决策。混在一起的话，handler 会同时承担 I/O 适配与业务编排，两件事的异常处理策略还正好相反：
 * I/O 异常必须吞掉以免打断容器，业务异常则要考虑是否该断开连接。
 *
 * <h2>在线状态为什么必须由 WebSocket 心跳来续</h2>
 *
 * <p>Redis 里的 {@code im:online:{userId}} 只有 90 秒 TTL，而 Sa-Token 的登录态有 7 天。
 * {@code SaTokenListenerImpl.doLogin} 只在登录那一刻写一次在线键，之后没有任何 HTTP 请求会再写它——
 * 用户完全可以登录后一直挂着 WebSocket 收消息而不发任何 REST 请求。
 * 若没有心跳续期，90 秒后他就会在好友列表里显示离线，而消息明明还在实时送达。
 * 所以「心跳刷在线键」不是锦上添花，是在线状态准确性的唯一来源。
 *
 * <p>另一个必须在这里写在线键的场景：用户关掉标签页再重新打开，前端用 localStorage 里的 token
 * 直接申请票据重连，整个过程没有 {@code doLogin} 事件。此时在线键早已过期，
 * 只有连接建立的这次 {@code setOnline} 能把他重新标成在线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsPresenceService {

    private final WsSessionManager sessionManager;
    private final ImProperties imProperties;

    /**
     * 在线状态与消息补推都是「有则更好」的协作方，用 {@link ObjectProvider} 弱引用。
     * 这与全项目对 {@code PushSpi} 的处理方式保持一致：模块缺席时降级运行，而不是启动失败。
     */
    private final ObjectProvider<OnlineStatusSpi> onlineStatusProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;

    /**
     * 连接建立后的完整上线流程。
     *
     * <p>三步的顺序是有讲究的：
     * <ol>
     *   <li>先注册进 {@code WsSessionManager}——注册之前这条连接对推送路径不可见，
     *       后面的欢迎帧和补推会全部落空；</li>
     *   <li>再刷在线键——让 REST 侧的 {@code isOnline} 查询立刻能看到他；</li>
     *   <li>最后补推离线消息——放在欢迎帧之后，前端就能靠「先收到 connected 再收到 message」
     *       这个顺序区分「补推的历史」和「实时新消息」。</li>
     * </ol>
     */
    public void onConnected(WsConnection connection) {
        sessionManager.register(connection);
        markOnline(connection);
        sessionManager.send(connection, welcomePacket(connection));
        pullOffline(connection);
    }

    /**
     * 处理一次心跳：刷新活跃时间与在线键，回一个 {@code pong}。
     *
     * <p>{@code pong} 是无条件回的，即使 Redis 写失败也要回。心跳的首要职责是让前端确认
     * 「这条连接还通着」，在线状态只是搭车的副作用；反过来做的话，Redis 抖一下前端就会判定断线并重连，
     * 一次缓存故障被放大成一场全站重连风暴。
     */
    public void onHeartbeat(WsConnection connection) {
        connection.touch();
        OnlineStatusSpi onlineStatus = onlineStatusProvider.getIfAvailable();
        if (onlineStatus != null) {
            try {
                onlineStatus.heartbeat(connection.userId(), connection.deviceId());
            } catch (RuntimeException e) {
                log.warn("心跳刷新在线状态失败，连接保持: userId={}, deviceId={}, err={}",
                        connection.userId(), connection.deviceId(), e.getMessage());
            }
        }
        sessionManager.send(connection, WsPacket.pong());
    }

    /**
     * 连接关闭后的下线流程。
     *
     * <p>这里刻意<strong>不</strong>调用 {@code PushSpi.notifyOnlineState} 去广播「某人已离线」。
     * 在线状态广播的所有权在 im-user：登录、注销、全端下线三个入口各自都已经广播过了。
     * 若连接断开也广播一次，一次网络抖动引发的重连就会对每个好友扇出一对
     * 「离线 + 在线」报文，好友列表跟着闪两下——而这两次状态变化对用户毫无意义。
     * Redis 在线键仍然会被正确清理，好友下次查询在线状态时看到的就是准的。
     */
    public void onDisconnected(WsConnection connection, CloseStatus status) {
        sessionManager.unregister(connection);
        OnlineStatusSpi onlineStatus = onlineStatusProvider.getIfAvailable();
        if (onlineStatus != null) {
            try {
                // 只下掉这一个设备位：多端在线时，关掉浏览器不该让手机端也变成离线
                onlineStatus.setOffline(connection.userId(), connection.deviceId());
            } catch (RuntimeException e) {
                log.warn("断开时清理在线状态失败: userId={}, deviceId={}, err={}",
                        connection.userId(), connection.deviceId(), e.getMessage());
            }
        }
        log.info("WebSocket 连接关闭: userId={}, deviceId={}, sessionId={}, code={}, reason={}",
                connection.userId(), connection.deviceId(), connection.sessionId(),
                status == null ? null : status.getCode(),
                status == null ? null : status.getReason());
    }

    /**
     * 补推离线消息。连接建立时自动调一次，客户端发 {@code pull-offline} 报文时也走这里。
     *
     * <p>一条消息一个 {@code MESSAGE} 报文，与实时推送的形状完全一致，
     * 前端不必为「补推」写第二套解析逻辑。批量塞进一个数组看似省帧，
     * 实际会逼着前端在 store 里区分两种 data 结构，而省下的那点开销对几十条消息毫无意义。
     *
     * <p>{@code listOffline} 自带确认位点推进语义（返回即视为已送达），所以服务端补推与
     * 前端重连后自己调 {@code GET /api/message/offline} 两条路是幂等的：
     * 谁先跑到谁拿到消息，另一个拿到空列表，不会重复推送。
     * 客户端反复发 {@code pull-offline} 同样安全，位点已推进就拿不到东西了。
     *
     * <p>已知取舍：若补推进行到一半连接断了，位点已经推进，剩下那部分不会再被补推。
     * 这不构成消息丢失——消息本身在 {@code im_message} 表里，未读数由
     * {@code im_conversation_member.unread_count} 独立维护，前端打开会话时拉的
     * {@code GET /api/message/history} 是直接查库的，照样能看到全部内容。
     * 真正丢掉的只是「服务端主动推」这一次机会，代价换来的是不必为一个可能已经断开的连接
     * 维护一套补推事务。
     *
     * <p>同步执行而不是丢到线程池：本项目开了 {@code spring.threads.virtual.enabled}，
     * 容器回调跑在虚拟线程上，阻塞不占用平台线程；而单次补推上限 500 条、
     * 约 100KB，远低于 {@code im.websocket.send-buffer-size-bytes} 的 512KB，
     * 不会触发装饰器的缓冲区护栏。异步化会把「补推」和「实时新消息」的顺序彻底打乱，
     * 得不偿失。
     */
    public void pullOffline(WsConnection connection) {
        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        if (messageSpi == null) {
            return;
        }
        List<MessageDTO> offline;
        try {
            offline = messageSpi.listOffline(connection.userId());
        } catch (RuntimeException e) {
            // 查询失败绝不能牵连连接：连接本身是好的，前端仍然可以通过 REST 自己拉历史
            log.error("离线消息查询失败，跳过补推: userId={}", connection.userId(), e);
            return;
        }
        if (offline == null || offline.isEmpty()) {
            return;
        }
        int delivered = 0;
        for (MessageDTO message : offline) {
            if (sessionManager.send(connection, WsPacket.of(WsMessageType.MESSAGE, message))) {
                delivered++;
            }
        }
        log.info("WebSocket 上线补推离线消息: userId={}, deviceId={}, 送达={}/{}",
                connection.userId(), connection.deviceId(), delivered, offline.size());
    }

    /**
     * 欢迎帧：告诉前端连接已就绪、该多久发一次心跳。
     *
     * <p>复用 {@link WsMessageType#NOTIFY} 而不是新增一个 {@code connected} 类型：
     * NOTIFY 的语义本就是「系统/业务通知」，data 里的 {@code action} 字段负责区分具体是哪一种，
     * 为一次性的握手确认去扩枚举、进而扩前端的 switch 分支并不划算。
     *
     * <p>不在这个 map 里放服务端时间戳：装箱后的 {@code Long} 会被全局的
     * Long→String 序列化规则转成字符串，前端还得 {@code Number()} 一次。
     * {@code WsPacket.timestamp} 本身是基本类型 {@code long}，序列化出来就是数字，直接用它校时即可。
     */
    private WsPacket welcomePacket(WsConnection connection) {
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("action", "connected");
        data.put("userId", connection.userId());
        data.put("deviceId", connection.deviceId());
        data.put("heartbeatSeconds", imProperties.getWebsocket().getHeartbeatIntervalSeconds());
        return WsPacket.of(WsMessageType.NOTIFY, data);
    }

    private void markOnline(WsConnection connection) {
        OnlineStatusSpi onlineStatus = onlineStatusProvider.getIfAvailable();
        if (onlineStatus == null) {
            return;
        }
        try {
            onlineStatus.setOnline(connection.userId(), connection.deviceId());
        } catch (RuntimeException e) {
            // 在线键写失败不影响连接可用性，最多是好友列表里暂时看不到他在线
            log.warn("写入在线状态失败: userId={}, deviceId={}, err={}",
                    connection.userId(), connection.deviceId(), e.getMessage());
        }
    }
}
