package com.im.live.service;

import com.im.common.constant.RedisKeys;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.live.config.LiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弹幕通道：房间 → 连接集合的注册表、广播、在线计数与限流。
 *
 * <h2>为什么弹幕不复用 IM 的群消息</h2>
 *
 * <p>看起来「直播间 = 群、弹幕 = 群消息」最省事，实际会把三个不相关的机制拖进来：
 * <ol>
 *   <li>观众必须是群成员才能发言（{@code MessageServiceImpl} 里群消息要过
 *       {@code conversationSpi.isMember} 与 {@code groupSpi.isMember} 双检），
 *       于是每个观众进房都要写 {@code im_group_member} + {@code im_conversation_member} 两张表；</li>
 *   <li>直播间会话会出现在所有观众的会话列表里，而 {@code is_deleted} 隐藏位会被
 *       下一条新消息重置——弹幕每秒好几条，等于永远隐藏不掉；</li>
 *   <li>群消息每条都要落库、发 seq、推离线，而弹幕是<b>可丢弃</b>的临时消息：
 *       没人需要「离线期间漏掉的 300 条弹幕」。</li>
 * </ol>
 * 所以这里走独立端点：不落库、不发号、断线即忘，只广播给当时在线的人。
 *
 * <h2>单实例约束</h2>
 *
 * <p>房间 → 连接集合是<b>本机内存表</b>，与远控中继（{@code RemoteRelayService}）同一约束：
 * 多实例部署时 A 实例上的观众收不到 B 实例收到的弹幕，需要再加一层 Redis pub/sub 扇出。
 * 在线人数走 Redis 所以是全局准的，弹幕广播是本机的——这个不一致是有意的取舍：
 * 人数错了只是显示问题，为此引入 pub/sub 会让单实例部署也背上额外依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveDanmakuService {

    /** 客户端 → 服务端：发弹幕 */
    public static final String TYPE_DANMAKU = "danmaku";
    /** 客户端 → 服务端：点赞（无内容，服务端合并计数后广播） */
    public static final String TYPE_LIKE = "like";
    /** 客户端 → 服务端：保活 */
    public static final String TYPE_PING = "ping";
    /** 服务端 → 客户端：在线人数变更 */
    public static final String TYPE_ONLINE = "online";
    /** 服务端 → 客户端：系统提示（进房欢迎、关播通知） */
    public static final String TYPE_SYSTEM = "system";
    /** 服务端 → 客户端：房间公告，进房时下发一次 */
    public static final String TYPE_NOTICE = "notice";
    /** 服务端 → 客户端：保活回应 */
    public static final String TYPE_PONG = "pong";
    /** 服务端 → 客户端：错误提示（限流、内容过长），不关连接 */
    public static final String TYPE_ERROR = "error";

    private final RedisUtil redisUtil;
    private final LiveProperties properties;
    private final JsonUtil jsonUtil;

    /** roomId → 房间内全部连接。用 newKeySet 而不是 CopyOnWriteArraySet：百人房间进出频繁，写时复制的拷贝成本更高 */
    private final Map<Long, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    /** sessionId → 所属房间，断开时反查（attributes 里也有，集中一处便于排查泄漏） */
    private final Map<String, Long> sessionRooms = new ConcurrentHashMap<>();

    /** sessionId → 弹幕限流窗口 */
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    /** roomId → 本场峰值在线 / 累计人次。关播时才落库，直播途中不写库 */
    private final Map<Long, int[]> stats = new ConcurrentHashMap<>();

    /** 点赞合并计数：roomId → 自上次广播以来累积的赞数，由巡检任务定期冲刷成一条广播 */
    private final Map<Long, Integer> pendingLikes = new ConcurrentHashMap<>();

    /* ==================== 进出房 ==================== */

    /**
     * 观众/主播进房。
     *
     * @return 进房后的在线人数，由 handler 作为第一帧回给客户端（省一次往返）
     */
    public int join(Long roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, key -> ConcurrentHashMap.newKeySet()).add(session);
        sessionRooms.put(session.getId(), roomId);
        String key = RedisKeys.liveOnline(roomId);
        long online = redisUtil.increment(key);
        redisUtil.expire(key, Duration.ofSeconds(properties.getOnlineKeyTtlSeconds()));

        int[] stat = stats.computeIfAbsent(roomId, ignored -> new int[2]);
        synchronized (stat) {
            stat[0] = Math.max(stat[0], (int) online);
            stat[1]++;
        }
        broadcastOnline(roomId, (int) online);
        return (int) online;
    }

    /**
     * 连接断开（含异常断开）。幂等：afterConnectionClosed 可能被容器重复回调。
     */
    public void leave(WebSocketSession session) {
        Long roomId = sessionRooms.remove(session.getId());
        rateWindows.remove(session.getId());
        if (roomId == null) {
            return;
        }
        Set<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                rooms.remove(roomId, sessions);
            }
        }
        String key = RedisKeys.liveOnline(roomId);
        long online = redisUtil.increment(key, -1);
        if (online <= 0) {
            // 计数落到 0 以下只可能是「重复 leave」或键被淘汰过，直接归零而不是留一个负数在 Redis 里
            redisUtil.set(key, "0", Duration.ofSeconds(properties.getOnlineKeyTtlSeconds()));
            online = 0;
        }
        broadcastOnline(roomId, (int) online);
    }

    /**
     * 关播：关闭房间内全部连接并清掉计数。
     *
     * <p>主动 close 而不是等观众自己发现流断了：m3u8 停止更新后播放器还要等
     * 若干个分片超时才会报错，那几十秒里观众看到的是「卡住的最后一帧」，
     * 不如直接给一帧系统提示再断开。
     */
    public void closeRoom(Long roomId, String reason) {
        sendSystem(roomId, reason);
        Set<WebSocketSession> sessions = rooms.remove(roomId);
        pendingLikes.remove(roomId);
        if (sessions != null) {
            for (WebSocketSession session : sessions) {
                sessionRooms.remove(session.getId());
                rateWindows.remove(session.getId());
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 关闭失败无副作用，容器随后会回调 afterConnectionClosed
                }
            }
        }
        redisUtil.delete(RedisKeys.liveOnline(roomId));
    }

    /* ==================== 广播 ==================== */

    /** 广播一帧 JSON。发送失败的连接就地摘除，不等容器回调 */
    public void broadcast(Long roomId, Map<String, Object> frame) {
        Set<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload = jsonUtil.toJson(frame);
        if (payload == null) {
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            try {
                // WebSocketSession 的并发发送不被允许（会抛 IllegalStateException），
                // 但 Spring 的 ConcurrentWebSocketSessionDecorator 需要包装才有；
                // 这里按 session 逐个同步发送，弹幕帧只有几百字节，串行发送不会成为瓶颈
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("弹幕下发失败，摘除连接: roomId={}, sessionId={}, {}", roomId, session.getId(), e.toString());
                sessions.remove(session);
            }
        }
    }

    public void sendSystem(Long roomId, String text) {
        broadcast(roomId, frame(TYPE_SYSTEM, "content", text));
    }

    public void broadcastOnline(Long roomId, int online) {
        broadcast(roomId, frame(TYPE_ONLINE, "count", online));
    }

    /** 主播端发的弹幕要标记身份，前端据此把气泡染色 */
    public void broadcastDanmaku(Long roomId, Long userId, String nickname, String avatar,
                                 boolean anchor, String content) {
        Map<String, Object> frame = frame(TYPE_DANMAKU, "content", content);
        frame.put("userId", userId);
        frame.put("nickname", nickname);
        frame.put("avatar", avatar);
        frame.put("anchor", anchor);
        frame.put("ts", System.currentTimeMillis());
        broadcast(roomId, frame);
    }

    /**
     * 点赞：先攒着，由巡检任务每 2 秒合并成一条广播。
     *
     * <p>百人房间同时点赞时逐条广播会把 WS 打满，而观众其实只关心「数字在涨」；
     * 合并后帧数从「每人每点一次一帧」降到「每房间每 2 秒一帧」。
     */
    public void addLikes(Long roomId, int count) {
        pendingLikes.merge(roomId, count, Integer::sum);
    }

    /** 冲刷点赞合并计数，返回被冲刷的房间数（供巡检日志） */
    public int flushLikes() {
        int flushed = 0;
        for (Map.Entry<Long, Integer> entry : pendingLikes.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }
            // 先摘除再广播：并发点赞会在广播期间继续累加，remove 拿到的是这一轮的确定值
            pendingLikes.remove(entry.getKey());
            broadcast(entry.getKey(), frame(TYPE_LIKE, "count", count));
            flushed++;
        }
        return flushed;
    }

    /* ==================== 查询 ==================== */

    public int onlineOf(Long roomId) {
        String value = redisUtil.get(RedisKeys.liveOnline(roomId));
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 本机该房间的连接数，用于判断是否有必要广播（没人就直接返回） */
    public int localSessions(Long roomId) {
        Set<WebSocketSession> sessions = rooms.get(roomId);
        return sessions == null ? 0 : sessions.size();
    }

    /**
     * 取出并清零本场统计（峰值在线 / 累计人次），关播时由房间服务落库。
     *
     * @return 长度为 2 的数组 {peakOnline, viewerTotal}；没有记录时返回 {0, 0}
     */
    public int[] drainStats(Long roomId) {
        int[] stat = stats.remove(roomId);
        return stat == null ? new int[]{0, 0} : stat;
    }

    /* ==================== 限流 ==================== */

    /**
     * 该连接这一秒还能不能发弹幕。
     *
     * <p>窗口按「整秒」切而不是滑动窗口：滑动窗口要存每条消息的时间戳，
     * 而弹幕限流的目的只是防刷爆，整秒窗口的突发上限已经够用，代价是一个 map entry。
     */
    public boolean allowDanmaku(WebSocketSession session) {
        long nowSecond = System.currentTimeMillis() / 1000L;
        RateWindow window = rateWindows.computeIfAbsent(session.getId(), ignored -> new RateWindow());
        synchronized (window) {
            if (window.second != nowSecond) {
                window.second = nowSecond;
                window.count = 0;
            }
            return ++window.count <= properties.getDanmakuPerSecond();
        }
    }

    /** 给单个连接回一帧（错误提示、pong），失败即摘除 */
    public void sendTo(WebSocketSession session, Map<String, Object> frame) {
        String payload = jsonUtil.toJson(frame);
        if (payload == null) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("单帧下发失败: sessionId={}, {}", session.getId(), e.toString());
        }
    }

    /** 构造一个只有两个键的帧，LinkedHashMap 保证 JSON 里 type 在最前，抓包时一眼能看出类型 */
    public Map<String, Object> frame(String type, String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>(8);
        map.put("type", type);
        map.put(key, value);
        return map;
    }

    /** 弹幕限流窗口：一秒一格 */
    private static final class RateWindow {
        private long second;
        private int count;
    }
}
