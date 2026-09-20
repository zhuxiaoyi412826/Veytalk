package com.im.websocket.manager;

import com.im.common.api.ResultCode;
import com.im.common.domain.WsPacket;
import com.im.common.enums.WsMessageType;
import com.im.common.util.JsonUtil;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket 连接注册表，全系统唯一的「谁在线、连接在哪」真相来源。
 *
 * <p>三级结构 {@code userId -> deviceId -> connections}：同一个用户可以同时从浏览器、
 * 桌面端、手机客户端在线，而消息要推给他的所有端；同一个设备位也允许挂多条连接 ——
 * 同一浏览器的多个标签页各自持有独立 WebSocket，这是用户期望的正常用法，
 * 不再做「同设备重复登录顶掉旧连接」（旧行为会把第二个标签页的建立变成第一个标签页的断线）。
 * 需要精确断开某个用户/某类设备时用 {@link #kickOut}（管理操作、封号、改密码后全端下线）。
 * 用单级 {@code sessionId -> connection} 再加一个 {@code userId -> sessionIds} 的倒排也能做，
 * 但两个索引就要保证同步，而在断开回调可能早于注册到达的现实里，同步这件事做不到无懈可击。
 *
 * <h2>为什么所有 close 都在 compute 之外</h2>
 *
 * <p>{@code compute}/{@code computeIfPresent} 会对同一个键加锁，而 Tomcat 的 {@code WsSession.close()}
 * 是<strong>同步</strong>的：它在调用线程上就地触发 {@code onClose} 监听器，
 * 一路走到 {@code ImWebSocketHandler.afterConnectionClosed}，再回到这里的 {@link #unregister}。
 * 于是「在 compute 的 lambda 里关闭连接」等于在持有键锁的情况下再次请求同一个键锁，
 * ConcurrentHashMap 会直接抛 {@code IllegalStateException: Recursive update}，
 * 连接既没关掉也没摘掉，永久残留在注册表里。所以本类的铁律是：
 * lambda 里只改数据结构，任何 {@code close} 一律等 compute 返回后再做。
 *
 * <h2>为什么发送前要先快照</h2>
 *
 * <p>发送失败会触发关闭，关闭会触发 unregister，unregister 会修改正在被遍历的那个 map。
 * 先 {@code List.copyOf} 拷一份再遍历，让「结构修改」和「遍历」在时间上错开，
 * 否则一次网络抖动就能换来一个 {@code ConcurrentModificationException}，把同一批里剩下的收件人全部漏推。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionManager {

    /**
     * 复用容器的 Jackson mapper，而不是自建一个。
     *
     * <p>{@code JsonUtil} 持有的是 Spring Boot 自动配置的 {@code ObjectMapper}，
     * 因此经过全局 Long→String 定制：报文里的雪花 ID 会以字符串写出，
     * 与 REST 接口的 JSON 形状完全一致，前端不必对两个通道写两套解析。
     */
    private final JsonUtil jsonUtil;

    /**
     * userId -> (deviceId -> connections)。
     *
     * <p>内层必须是 ConcurrentHashMap：快照遍历发生在 {@code registry.get()} 之后，
     * 已经不在外层 compute 的键锁保护范围内，此时若内层是 HashMap，
     * 遍历与写入并发就可能读到撕裂的桶链表。每个设备位的连接列表用
     * CopyOnWriteArrayList：注册/摘除是低频写，推送是高频读，读时不必加锁。
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>>> registry =
            new ConcurrentHashMap<>();
    
    /**
     * 登记一条新连接。
     *
     * <p>同一设备位的新连接（刷新、多标签页）与旧连接共存，不再顶号：
     * 顶号会让「同浏览器再开一个页签」把第一个页签挤下线，而两端共用同一个登录 token，
     * 被顶的一方既没断网也没换账号，收到的 kickout 纯属误伤。
     */
    public void register(WsConnection connection) {
        Long userId = connection.userId();
        registry.compute(userId, (key, devices) -> {
            ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>> map =
                    devices != null ? devices : new ConcurrentHashMap<>(4);
            map.computeIfAbsent(connection.deviceId(), deviceId -> new CopyOnWriteArrayList<>()).add(connection);
            return map;
        });
        log.info("WebSocket 连接建立: userId={}, deviceId={}, sessionId={}",
                userId, connection.deviceId(), connection.sessionId());
    }

    /**
     * 摘除一条连接。
     *
     * <p>用引用相等做身份比对删除，这是本类最关键的一处防御。
     * 页面刷新时，新连接的 register 与旧连接的 close 回调谁先到达是没有保证的——
     * 浏览器先建立新连接、旧连接的 FIN 稍后才处理完，是很常见的顺序。
     * 若这里按 deviceId 整键删除，会把同设备位上其他正常工作的连接（多标签页）一起摘掉。
     */
    public void unregister(WsConnection connection) {
        if (connection == null) {
            return;
        }
        registry.computeIfPresent(connection.userId(), (userId, devices) -> {
            devices.computeIfPresent(connection.deviceId(), (deviceId, list) -> {
                list.removeIf(item -> item == connection);
                return list.isEmpty() ? null : list;
            });
            // 该用户最后一个端也走了就把外层键一起删掉，否则长时间运行后 registry 里全是空 map
            return devices.isEmpty() ? null : devices;
        });
        log.debug("WebSocket 连接摘除: userId={}, deviceId={}, sessionId={}",
                connection.userId(), connection.deviceId(), connection.sessionId());
    }

    /**
     * 推送给一条具体连接。
     *
     * <p>欢迎帧与上线补推走这里而不是 {@link #pushToDevice}：那两者只该发给刚建立的这一端，
     * 而且补推是成百条报文的循环，拿着已经在手的 {@code connection} 直接写，
     * 不必每条都重新走一遍 {@code registry.get}。
     */
    public boolean send(WsConnection connection, WsPacket packet) {
        if (connection == null) {
            return false;
        }
        String payload = write(packet);
        return payload != null && sendPayload(connection, payload);
    }

    /**
     * 推送给某个用户的全部在线端。
     *
     * @return 至少有一端投递成功返回 {@code true}；用户不在线返回 {@code false}，
     *         调用方据此判断是否需要走离线补偿
     */
    public boolean pushToUser(Long userId, WsPacket packet) {
        List<WsConnection> targets = snapshot(userId);
        if (targets.isEmpty()) {
            return false;
        }
        String payload = write(packet);
        if (payload == null) {
            return false;
        }
        boolean delivered = false;
        for (WsConnection connection : targets) {
            delivered |= sendPayload(connection, payload);
        }
        return delivered;
    }

    /**
     * 只推送给某个用户的指定设备端。
     *
     * @param deviceId 为空时退化为推给该用户的全部端
     */
    public boolean pushToDevice(Long userId, String deviceId, WsPacket packet) {
        if (userId == null) {
            return false;
        }
        if (TextUtil.isBlank(deviceId)) {
            return pushToUser(userId, packet);
        }
        List<WsConnection> targets = snapshot(userId, deviceId);
        if (targets.isEmpty()) {
            return false;
        }
        String payload = write(packet);
        if (payload == null) {
            return false;
        }
        boolean delivered = false;
        for (WsConnection connection : targets) {
            delivered |= sendPayload(connection, payload);
        }
        return delivered;
    }

    /**
     * 批量推送，用于群发与会话成员通知。
     *
     * <p>整个报文只序列化一次：一个 200 人的群若按连接数序列化，
     * 同一条消息就要跑 200 遍 Jackson，而产出的字节完全相同。
     *
     * @param userIds       收件人，内部会去重——上游按会话成员拼列表时可能带上重复项，
     *                      重复推送会让客户端把同一条消息渲染两遍
     * @param excludeUserId 需要排除的用户，通常是发送者自己；可为空
     */
    public void pushToUsers(Collection<Long> userIds, Long excludeUserId, WsPacket packet) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String payload = write(packet);
        if (payload == null) {
            return;
        }
        for (Long userId : new LinkedHashSet<>(userIds)) {
            if (userId == null || userId.equals(excludeUserId)) {
                continue;
            }
            for (WsConnection connection : snapshot(userId)) {
                sendPayload(connection, payload);
            }
        }
    }

    /**
     * 广播给当前所有在线连接。仅用于全站公告这类场景，业务消息不要走这里。
     */
    public void broadcast(WsPacket packet) {
        String payload = write(packet);
        if (payload == null) {
            return;
        }
        for (ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>> devices : List.copyOf(registry.values())) {
            for (CopyOnWriteArrayList<WsConnection> list : List.copyOf(devices.values())) {
                for (WsConnection connection : list) {
                    sendPayload(connection, payload);
                }
            }
        }
    }

    /**
     * 踢下线：先送达 {@code kickout} 报文，再关闭连接。
     *
     * <p>顺序不能反。关闭之后 socket 已经不可写，报文发不出去，
     * 前端只会看到一个 {@code code=1008} 的关闭事件，分不清是自己被顶号还是服务端出了故障，
     * 于是按断线逻辑重连——而它的登录态已经被 Sa-Token 注销，重连必然失败，用户看到的是无限转圈。
     *
     * <p>这里<strong>不</strong>调用 {@code StpUtil.kickout}：注销登录态是调用方的职责，
     * 若这里也调一次，两条路径会互相触发，同一次踢出产生两轮注销。
     *
     * @param deviceId 为空表示踢掉该用户的全部端
     */
    public void kickOut(Long userId, String deviceId, String reason) {
        if (userId == null) {
            return;
        }
        List<WsConnection> targets = TextUtil.isBlank(deviceId)
                ? snapshot(userId)
                : snapshot(userId, deviceId);
        if (targets.isEmpty()) {
            return;
        }
        String payload = write(kickoutPacket(userId, deviceId, reason));
        for (WsConnection connection : targets) {
            if (payload != null) {
                // 送达失败也要继续关：踢下线是状态变更，不能因为对方网络已经断了就不生效
                sendPayload(connection, payload);
            }
            closeQuietly(connection, CloseStatus.POLICY_VIOLATION);
            unregister(connection);
        }
        log.info("WebSocket 踢下线: userId={}, deviceId={}, 连接数={}, reason={}",
                userId, deviceId, targets.size(), reason);
    }

    /**
     * 用户当前是否有存活的连接。
     *
     * <p>只看本进程的注册表，不查 Redis。这个语义是「我能不能立刻推给他」，
     * 而 Redis 里的在线键表达的是「他最近有没有心跳」，两者不等价：
     * 多实例部署时用户可能连在另一个节点上，此时这里必须返回 false，
     * 调用方才会走离线补偿；若这里去查 Redis 并返回 true，消息就会被推进一个没有连接的黑洞。
     */
    public boolean isUserOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>> devices = registry.get(userId);
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        // 还要过一遍 isOpen：断开回调尚未执行时，注册表里可能残留一条已经关掉的连接
        return devices.values().stream()
                .anyMatch(list -> list.stream().anyMatch(WsConnection::isOpen));
    }

    /**
     * 关闭空闲超过 {@code timeoutMillis} 的连接，由定时任务驱动。
     *
     * @return 本次关闭的连接数
     */
    public int closeIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() - timeoutMillis;
        int closed = 0;
        for (ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>> devices : List.copyOf(registry.values())) {
            for (CopyOnWriteArrayList<WsConnection> list : List.copyOf(devices.values())) {
                for (WsConnection connection : list) {
                    if (connection.lastActiveTime() > deadline) {
                        continue;
                    }
                    log.info("WebSocket 心跳超时，主动断开: userId={}, deviceId={}, 空闲={}ms",
                            connection.userId(), connection.deviceId(), connection.idleMillis());
                    closeQuietly(connection, CloseStatus.SESSION_NOT_RELIABLE.withReason("heartbeat timeout"));
                    unregister(connection);
                    closed++;
                }
            }
        }
        return closed;
    }

    /** 当前在线用户数 */
    public int onlineUserCount() {
        return registry.size();
    }

    /** 当前连接总数，同一用户多端、同端多标签会分别计数 */
    public int connectionCount() {
        return registry.values().stream()
                .flatMap(devices -> devices.values().stream())
                .mapToInt(List::size)
                .sum();
    }

    // ==================== 内部实现 ====================

    /** 取某用户全部连接的快照，返回不可变列表，可安全遍历 */
    private List<WsConnection> snapshot(Long userId) {
        if (userId == null) {
            return List.of();
        }
        ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>> devices = registry.get(userId);
        if (devices == null || devices.isEmpty()) {
            return List.of();
        }
        return devices.values().stream().flatMap(List::stream).toList();
    }

    /** 取某用户指定设备位全部连接的快照（同设备位可能有多条，如多标签页） */
    private List<WsConnection> snapshot(Long userId, String deviceId) {
        ConcurrentHashMap<String, CopyOnWriteArrayList<WsConnection>> devices = registry.get(userId);
        CopyOnWriteArrayList<WsConnection> list = devices == null ? null : devices.get(deviceId);
        return list == null || list.isEmpty() ? List.of() : List.copyOf(list);
    }

    /**
     * 真正写出一个帧，并处理失败后的连接清理。
     *
     * @return 是否投递成功
     */
    private boolean sendPayload(WsConnection connection, String payload) {
        if (payload == null) {
            return false;
        }
        if (!connection.isOpen()) {
            // 已经关了但回调还没摘除，顺手清掉，避免注册表里堆积死连接
            unregister(connection);
            return false;
        }
        try {
            connection.send(payload);
            return true;
        } catch (SessionLimitExceededException e) {
            // ConcurrentWebSocketSessionDecorator 判定这条连接已不可靠：
            // 要么单次发送超过 sendTimeLimitMs，要么积压超过 sendBufferSizeBytes。
            // 此时装饰器已经放弃维护它的发送队列，必须由我们关闭，否则它会永远挂在注册表里假装在线
            log.warn("WebSocket 会话超出发送限制，关闭连接: userId={}, deviceId={}, {}",
                    connection.userId(), connection.deviceId(), e.getMessage());
            closeQuietly(connection, CloseStatus.SESSION_NOT_RELIABLE);
            unregister(connection);
            return false;
        } catch (IOException | RuntimeException e) {
            // RuntimeException 也要接住：对端异常断开时 Tomcat 可能抛出未包装的运行时异常，
            // 而这条路径处在群发循环里，一个漏出的异常会让同批剩下的收件人全部收不到消息
            log.warn("WebSocket 推送失败: userId={}, deviceId={}, sessionId={}, err={}",
                    connection.userId(), connection.deviceId(), connection.sessionId(), e.getMessage());
            closeQuietly(connection, CloseStatus.SERVER_ERROR);
            unregister(connection);
            return false;
        }
    }

    /**
     * 把报文序列化成 JSON，失败返回 {@code null}。
     *
     * <p>每个报文只序列化一次，供一批连接复用。
     */
    private String write(WsPacket packet) {
        if (packet == null) {
            return null;
        }
        String payload = jsonUtil.toJson(packet);
        if (payload == null) {
            log.error("WebSocket 报文序列化失败，本次推送放弃: type={}", packet.getType());
        }
        return payload;
    }

    private WsPacket kickoutPacket(Long userId, String deviceId, String reason) {
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("userId", userId);
        if (TextUtil.isNotBlank(deviceId)) {
            data.put("deviceId", deviceId);
        }
        data.put("reason", TextUtil.isBlank(reason) ? ResultCode.USER_KICKED_OUT.getMessage() : reason);
        return WsPacket.of(WsMessageType.KICKOUT, data);
    }

    /**
     * 静默关闭连接。
     *
     * <p>异常只记 debug 不上抛：关闭失败没有可恢复的动作，容器自己的空闲超时最终会回收这条连接，
     * 而把异常抛回调用方会让一次失败的关闭打断整批推送——代价远大于收益。
     */
    private void closeQuietly(WsConnection connection, CloseStatus status) {
        try {
            if (connection.isOpen()) {
                connection.session().close(status);
            }
        } catch (Exception e) {
            log.debug("关闭 WebSocket 连接失败: sessionId={}, err={}", connection.sessionId(), e.getMessage());
        }
    }
}
