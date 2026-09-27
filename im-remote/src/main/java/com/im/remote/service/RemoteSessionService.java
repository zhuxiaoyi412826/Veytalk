package com.im.remote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.ResultCode;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.remote.config.RemoteProperties;
import com.im.remote.dto.vo.RemoteSessionVO;
import com.im.remote.entity.RemoteAuditLog;
import com.im.remote.entity.RemoteDevice;
import com.im.remote.entity.RemoteSession;
import com.im.remote.manager.AgentRegistry;
import com.im.remote.mapper.RemoteAuditLogMapper;
import com.im.remote.mapper.RemoteSessionMapper;
import com.im.remote.protocol.RemoteEnvelope;
import com.im.remote.protocol.RemoteProtocol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 远程会话状态机：inviting → active → ended / rejected。
 *
 * <p>邀请、授权、结束都必须「先落库再发通知」：库里的状态是唯一事实，
 * WS 帧只是它的投影。投影丢了（Agent 恰好掉线）状态机依然正确，
 * 超时巡检会把悬空的 inviting 会话收尾。
 *
 * <p>ticket 是一次性的控制端连接凭证：生成时只写 Redis 不消费，
 * 控制端 WS 发出 control-ready 帧时才原子取回并删除——
 * 握手到首帧之间连接可能失败，握手就消费会让一次失败的连接烧掉整个邀请。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteSessionService {

    private final RemoteSessionMapper sessionMapper;
    private final RemoteAuditLogMapper auditLogMapper;
    private final RemoteDeviceService deviceService;
    private final AgentRegistry agentRegistry;
    private final RemoteProperties properties;
    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final RemotePunchService punchService;
    private final org.springframework.beans.factory.ObjectProvider<RemoteRelayService> relayServiceProvider;
    private final ObjectProvider<UserQuerySpi> userQuerySpiProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 会话密钥内存窗口：ticket/aesKey/路由键不入库（实体 exist=false），
     * 靠这张表从 invite 活到 finish——此前它们只存在于方法局部变量里，
     * 方法返回即丢，detail() 重查库永远拿不到 ticket，控制端永远建不起连接。
     */
    private final Map<Long, SessionSecrets> secrets = new java.util.concurrent.ConcurrentHashMap<>();

    /** 单个会话的内存密钥包；字段volatile：写在 WS 回调线程、读在 REST 线程 */
    public static final class SessionSecrets {
        volatile String ticket;
        volatile byte[] aesKey;
        volatile String deviceKey;
        /** 直连一次性凭证：Agent 与控端各拿一份，握手时两端比对；不落库、不进日志 */
        volatile String directToken;
    }

    /** 延迟取中继 Bean：Relay ↔ SessionService 互为调用但构造不成环 */
    private RemoteRelayService relayService() {
        return relayServiceProvider.getObject();
    }

    /** 发起邀请（同账号「我的设备」路径）：校验设备可用性 → 建会话 → 向 Agent 推 invite 帧 */
    public Map<String, Object> invite(String deviceId, String permission) {
        Long inviter = SecurityUtil.getUserId();
        checkEnabled();
        RemoteDevice device = deviceService.find(inviter, deviceId);
        if (device == null) {
            throw new BusinessException(ResultCode.REMOTE_DEVICE_NOT_FOUND);
        }
        AgentRegistry.AgentInfo agent = agentRegistry.get(inviter, deviceId);
        int status = device.getStatus() == null ? RemoteDevice.STATUS_OFFLINE : device.getStatus();
        if (agent == null || status == RemoteDevice.STATUS_OFFLINE) {
            throw new BusinessException(ResultCode.REMOTE_DEVICE_OFFLINE);
        }
        if (status != RemoteDevice.STATUS_IDLE) {
            // busy（含未收尾的脏状态）与 refuse（用户开关）分开回错，前端文案能直接告诉用户下一步
            throw new BusinessException(status == RemoteDevice.STATUS_REFUSE
                    ? ResultCode.REMOTE_DEVICE_REFUSED : ResultCode.REMOTE_DEVICE_BUSY);
        }
        return doInvite(inviter, agent, device, permission);
    }

    /**
     * 凭识别码发起邀请（ToDesk 式跨账号路径）：控制方必须是登录用户（REST 层的
     * {@code @SaCheckLogin} 保证「用自己账号密码才能控制别人」），被控方无需注册账号。
     *
     * <p>识别码只用于路由到在线 Agent，不是授权：真正的授权仍是 Agent 本地弹窗，
     * 猜对码也只能让对方弹一个确认框，拒绝即终止。错误文案故意合并「不在线/码错」
     * 两种情况，不给探测者区分「码存在但离线」的反馈面；接口另有用户维度限流。
     */
    public Map<String, Object> inviteByCode(String accessCode, String permission) {
        Long inviter = SecurityUtil.getUserId();
        checkEnabled();
        String code = accessCode == null ? "" : accessCode.trim().toUpperCase();
        AgentRegistry.AgentInfo agent = agentRegistry.getByCode(code);
        if (agent == null) {
            throw new BusinessException(ResultCode.REMOTE_DEVICE_OFFLINE, "设备不在线或识别码不正确");
        }
        RemoteDevice device = deviceService.find(agent.getUserId(), agent.getDeviceId());
        int status = device == null || device.getStatus() == null
                ? RemoteDevice.STATUS_IDLE : device.getStatus();
        if (status == RemoteDevice.STATUS_REFUSE) {
            throw new BusinessException(ResultCode.REMOTE_DEVICE_REFUSED);
        }
        if (status == RemoteDevice.STATUS_BUSY) {
            throw new BusinessException(ResultCode.REMOTE_DEVICE_BUSY);
        }
        return doInvite(inviter, agent, device, permission);
    }

    /** 两条邀请路径的共同尾巴：防占位、建会话、推 invite 帧 */
    private Map<String, Object> doInvite(Long inviter, AgentRegistry.AgentInfo agent,
                                         RemoteDevice device, String permission) {
        String perm = RemoteSession.PERMISSION_READONLY.equals(permission)
                ? RemoteSession.PERMISSION_READONLY : RemoteSession.PERMISSION_OPERATE;
        String deviceId = agent.getDeviceId();
        // 同一设备同一时刻只允许一个进行中的邀请，新邀请顶掉旧的（旧邀请方会收到超时结束通知）
        List<RemoteSession> pending = sessionMapper.selectList(new LambdaQueryWrapper<RemoteSession>()
                .eq(RemoteSession::getDeviceId, deviceId)
                .eq(RemoteSession::getStatus, RemoteSession.STATUS_INVITING));
        for (RemoteSession old : pending) {
            finishSession(old.getId(), "superseded", 0L);
        }

        byte[] aesKey = properties.isAes() ? randomKey() : null;
        RemoteSession session = RemoteSession.builder()
                .inviteeUserId(agent.getUserId())
                .deviceId(deviceId)
                .inviterUserId(inviter)
                .permission(perm)
                .status(RemoteSession.STATUS_INVITING)
                .bytes(0L)
                .build();
        session.setAesKey(aesKey);
        session.setDevice(agentRegistry.key(agent.getUserId(), deviceId));
        sessionMapper.insert(session);
        // 密钥包必须在 insert 之后登记：ID 由 MyBatis-Plus 在插入时生成，之前是 null（ConcurrentHashMap 拒收 null 键）
        SessionSecrets sec = secrets.computeIfAbsent(session.getId(), k -> new SessionSecrets());
        sec.aesKey = aesKey;
        sec.deviceKey = session.getDevice();

        UserBriefDTO inviterBrief = userQuerySpiProvider.getIfAvailable() == null
                ? null : userQuerySpiProvider.getObject().getById(inviter);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", String.valueOf(session.getId()));
        data.put("permission", perm);
        data.put("inviterNickname", inviterBrief == null ? "我" : inviterBrief.getNickname());
        RemoteEnvelope invite = RemoteEnvelope.of(RemoteProtocol.TYPE_INVITE, data);
        invite.setSeq(agent.nextSeq());
        agentRegistry.send(agent, jsonUtil.toJson(invite));
        recordAudit(session.getId(), "invite", "permission=" + perm, RemoteAuditLog.ACTOR_INVITER);
        log.info("远程邀请已发出: sessionId={}, deviceId={}, permission={}", session.getId(), deviceId, perm);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", String.valueOf(session.getId()));
        result.put("deviceName", device == null ? deviceId : device.getDeviceName());
        result.put("permission", perm);
        return result;
    }

    private void checkEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程控制功能未启用");
        }
    }

    /** 会话详情：控制端轮询授权进度，active 后返回 ticket 与 aesKey */
    public Map<String, Object> detail(Long sessionId) {
        RemoteSession session = requireOwned(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", String.valueOf(session.getId()));
        result.put("status", session.getStatus());
        result.put("permission", session.getPermission());
        result.put("deviceId", session.getDeviceId());
        // 设备行归属被控方（跨账号/匿名场景下不是 inviter），先按 invitee 查、查不到再退回 inviter
        RemoteDevice device = deviceService.find(session.getInviteeUserId(), session.getDeviceId());
        if (device == null) {
            device = deviceService.find(session.getInviterUserId(), session.getDeviceId());
        }
        result.put("deviceName", device == null ? session.getDeviceId() : device.getDeviceName());
        result.put("endReason", session.getEndReason());
        // 活跃会话的流量从内存绑定实时取，避免每帧落库
        result.put("bytes", relayService().boundBytes(sessionId));
        if (RemoteSession.STATUS_ACTIVE.equals(session.getStatus())) {
            SessionSecrets sec = secrets.get(sessionId);
            if (sec != null && sec.ticket != null) {
                result.put("ticket", sec.ticket);
                result.put("aesKey", sec.aesKey == null ? null : Base64.getEncoder().encodeToString(sec.aesKey));
            }
        }
        result.put("direct", directInfo(session, true));
        return result;
    }

    /**
     * 直连能力与凭证：控端据此决定要不要试 LAN/打洞，Agent 据此决定要不要起监听口。
     *
     * <p>{@code punchAvailable} 为假时控端不会去问反射器（开发环境 punch-host 通不通往往取决于
     * 本机防火墙，少一次无效往返少一个不确定因素）；{@code active} 为假时直连已无意义，
     * 不再下发 token，避免一个已结束的会话还能凭旧 token 去敲被控端的端口。
     */
    private Map<String, Object> directInfo(RemoteSession session, boolean includeToken) {
        Map<String, Object> direct = new LinkedHashMap<>();
        RemoteProperties.Direct config = properties.getDirect();
        boolean usable = directAvailable() && session != null
                && RemoteSession.STATUS_ACTIVE.equals(session.getStatus());
        SessionSecrets sec = session == null ? null : secrets.get(session.getId());
        direct.put("enabled", usable && sec != null && sec.directToken != null);
        direct.put("lan", config.isLanEnabled());
        direct.put("udp", config.isUdpEnabled() && punchService.isRunning());
        direct.put("punchHost", config.getPunchHost() == null ? "" : config.getPunchHost());
        direct.put("punchPort", config.getPunchPort());
        direct.put("mtu", config.getMtu());
        direct.put("handshakeTimeoutMs", config.getHandshakeTimeoutMs());
        direct.put("token", usable && includeToken && sec != null ? sec.directToken : null);
        return direct;
    }

    /** 服务端侧直连是否可用：总开关 + 模块开关同时打开才算 */
    public boolean directAvailable() {
        return properties.isEnabled() && properties.getDirect().isEnabled();
    }

    /** 中继下发 session-start 时取直连参数（含 token），避开把密钥包外泄给其它调用方 */
    public Map<String, Object> directParams(Long sessionId) {
        RemoteSession session = sessionMapper.selectById(sessionId);
        return directInfo(session, true);
    }

    /**
     * 直连流量上报：服务端不加密也不能解密载荷，只记账。
     *
     * <p>两端口径必然不完全相等（丢包、重传、上报时刻不同），取大者作为会话总直连流量：
     * 带宽评估宁可估高不可估低，估低了会让人误以为直连省得没那么多。
     */
    public void recordDirectStats(Long sessionId, String role, long down, long up, String path) {
        RemoteRelayService.Binding binding = relayService().binding(sessionId);
        if (binding != null) {
            binding.applyDirectStats(role, down, up, path);
        }
        recordAudit(sessionId, "direct-stats", "role=" + role + ", path=" + path
                        + ", down=" + down + ", up=" + up,
                "agent".equals(role) ? RemoteAuditLog.ACTOR_INVITEE : RemoteAuditLog.ACTOR_INVITER);
    }

    /** 直连建立/失败的状态变更，只进审计与日志，不改会话状态机 */
    public void recordDirectEvent(Long sessionId, String action, String detail, boolean fromAgent) {
        recordAudit(sessionId, action, detail,
                fromAgent ? RemoteAuditLog.ACTOR_INVITEE : RemoteAuditLog.ACTOR_INVITER);
        log.info("远程直连事件: sessionId={}, action={}, detail={}, fromAgent={}", sessionId, action, detail, fromAgent);
    }

    /** Agent 上报 accept：校验归属 → 生成一次性 ticket → 会话转 active */
    public void handleAccept(AgentRegistry.AgentInfo agent, RemoteEnvelope env) {
        Long sessionId = resolveSessionId(env);
        RemoteSession session = requireActivePendingDevice(sessionId, agent);
        // 授权方可降档：请求 operate 但只给 readonly 是合法决定，反向绝不允许
        String granted = env.getData() == null ? null : str(env.getData().get("permission"));
        if (RemoteSession.PERMISSION_READONLY.equals(granted)) {
            session.setPermission(RemoteSession.PERMISSION_READONLY);
        }
        String ticket = randomTicket();
        redisUtil.set(RemoteProtocol.REDIS_TICKET_PREFIX + ticket,
                String.valueOf(session.getId()), Duration.ofSeconds(properties.getControlTicketTtlSeconds()));
        SessionSecrets sec = secrets.computeIfAbsent(sessionId, k -> new SessionSecrets());
        sec.ticket = ticket;
        // 直连凭证与 ticket 同时生成：两端都在授权这一刻之后才尝试建直连，早生成没有额外暴露窗口；
        // Agent 拿到的是一份内存副本、控端从 detail() 拿，服务端不再参与数据面校验
        if (directAvailable()) {
            sec.directToken = randomTicket();
        }
        session.setTicket(ticket);
        session.setStatus(RemoteSession.STATUS_ACTIVE);
        session.setStartTime(LocalDateTime.now());
        sessionMapper.updateById(session);
        // 授权即占用：设备转 busy，直到会话收尾时回到空闲
        RemoteDevice device = deviceService.find(session.getInviteeUserId(), session.getDeviceId());
        if (device != null) {
            deviceService.updateStatus(device.getId(), RemoteDevice.STATUS_BUSY);
        }
        recordAudit(sessionId, "accept", "grantedPermission=" + session.getPermission(),
                RemoteAuditLog.ACTOR_INVITEE);
        log.info("远程会话已授权: sessionId={}, deviceId={}", sessionId, agent.getDeviceId());
    }

    /** Agent 上报 reject：会话终结为 rejected，设备回到空闲 */
    public void handleReject(AgentRegistry.AgentInfo agent, RemoteEnvelope env) {
        Long sessionId = resolveSessionId(env);
        requireActivePendingDevice(sessionId, agent);
        finishSession(sessionId, "rejected", 0L);
        recordAudit(sessionId, "reject", null, RemoteAuditLog.ACTOR_INVITEE);
    }

    /** 控制端主动结束（REST 通道）：绑定中的走中继收尾（含双向通知），未绑定的直接落库 */
    public void endByInviter(Long sessionId) {
        requireOwned(sessionId);
        RemoteRelayService.Binding binding = relayService().binding(sessionId);
        if (binding != null) {
            relayService().closeIdleBinding(binding, "inviter-end");
            return;
        }
        finishSession(sessionId, "inviter-end", 0L);
    }

    /**
     * 会话收尾（幂等）：状态置 ended、写结束时间/原因/流量、设备回空闲、清 ticket。
     *
     * <p>bytes 从内存绑定取，只在收尾时一次性落库——每帧都 UPDATE 会把中继热路径
     * 变成写数据库，1MB/s 的流量足以让审计写反压转发。
     */
    public void finishSession(Long sessionId, String reason, long bytes) {
        RemoteSession session = sessionMapper.selectById(sessionId);
        if (session == null || RemoteSession.STATUS_ENDED.equals(session.getStatus())) {
            return;
        }
        secrets.remove(sessionId);
        LocalDateTime endTime = LocalDateTime.now();
        // 时长是为了把总流量折算成平均码率：光有 bytes 分不清「短会话码率很高」还是「挂了一整晚」
        long seconds = session.getStartTime() == null ? 0
                : java.time.Duration.between(session.getStartTime(), endTime).getSeconds();
        RemoteSession patch = RemoteSession.builder()
                .id(sessionId)
                .status(RemoteSession.STATUS_ENDED)
                .endTime(endTime)
                .endReason(reason)
                .bytes(bytes)
                .build();
        sessionMapper.updateById(patch);
        RemoteDevice device = deviceService.find(session.getInviteeUserId(), session.getDeviceId());
        if (device != null && device.getStatus() != null && device.getStatus() != RemoteDevice.STATUS_OFFLINE) {
            deviceService.updateStatus(device.getId(), RemoteDevice.STATUS_IDLE);
        }
        recordAudit(sessionId, "session-end", "reason=" + reason + ", bytes=" + bytes, actorOfReason(reason));
        // 带宽核算用的一行：总流量（双向）+ 时长 + 平均码率（Mbps 按 10^6，与云厂商规格表同口径）
        log.info("远程会话已结束: sessionId={}, reason={}, 时长={}s, 总流量={}MB, 平均码率={}Mbps",
                sessionId, reason, seconds,
                String.format("%.2f", bytes / 1024.0 / 1024),
                String.format("%.2f", seconds > 0 ? bytes * 8.0 / seconds / 1_000_000 : 0));
    }

    /** 中继绑定成功后调用：控制端已凭票接入 */
    public void onControlBound(long sessionId) {
        recordAudit(sessionId, "control-bound", null, RemoteAuditLog.ACTOR_INVITER);
    }

    /** 消费一次性 ticket：校验 Redis 票据归属会话，成功后立即删除；回填内存路由字段 */
    public RemoteSession consumeTicket(String ticket, Long userId) {
        String sid = redisUtil.getAndDelete(RemoteProtocol.REDIS_TICKET_PREFIX + ticket);
        if (sid == null) {
            throw new BusinessException(ResultCode.REMOTE_TICKET_INVALID);
        }
        RemoteSession session = sessionMapper.selectById(Long.valueOf(sid));
        if (session == null || !RemoteSession.STATUS_ACTIVE.equals(session.getStatus())
                || !userId.equals(session.getInviterUserId())) {
            throw new BusinessException(ResultCode.REMOTE_TICKET_INVALID);
        }
        // 票据就是这次接入的全部凭证：aesKey/device 路由键只随消费成功返回
        SessionSecrets sec = secrets.get(session.getId());
        session.setTicket(ticket);
        session.setAesKey(sec == null ? null : sec.aesKey);
        session.setDevice(sec != null && sec.deviceKey != null
                ? sec.deviceKey : agentRegistry.key(session.getInviteeUserId(), session.getDeviceId()));
        return session;
    }

    /** ticket 是否仍有效（握手阶段只做存在性校验，不消费），顺带返回其绑定的 sessionId */
    public String ticketSession(String ticket) {
        try {
            return redisUtil.get(RemoteProtocol.REDIS_TICKET_PREFIX + ticket);
        } catch (Exception e) {
            return null;
        }
    }

    /** 控制端 control-ready 入口：消费票据并交给中继绑定 */
    public void consumeTicketAndBind(RemoteRelayService.ControlInfo control) {
        try {
            RemoteSession session = consumeTicket(control.getTicket(), control.getUserId());
            relayService().onControlReady(control, session);
        } catch (BusinessException e) {
            log.warn("控制端票据消费失败: sessionId={}, code={}", control.getSessionId(), e.getCode());
        }
    }

    /** 中继转发的 audit 帧落库（全部来自被控端 Agent 的自报） */
    public void recordFrameAudit(long sessionId, RemoteEnvelope env) {
        Map<String, Object> data = env.getData();
        String action = data == null ? "unknown" : str(data.get("action"));
        String detail = data == null ? null : str(data.get("detail"));
        recordAudit(sessionId, action, detail, RemoteAuditLog.ACTOR_INVITEE);
    }

    /** 只读模式下的输入帧拦截记录（同一会话高频触发，只留首个线索） */
    public void recordInputBlocked(long sessionId, String frameType) {
        Long existing = auditLogMapper.selectCount(new LambdaQueryWrapper<RemoteAuditLog>()
                .eq(RemoteAuditLog::getSessionId, sessionId)
                .eq(RemoteAuditLog::getAction, "input-blocked"));
        if (existing != null && existing > 10) {
            return;
        }
        // 被拦下的是控制端发出的输入帧，责任在发起方而不是执行方
        recordAudit(sessionId, "input-blocked", "type=" + frameType, RemoteAuditLog.ACTOR_INVITER);
    }

    /** 空闲/超时巡检入口：把超时未授权的 inviting 会话收尾 */
    public void expireInvitingTimeouts() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(properties.getInviteTimeoutSeconds());
        List<RemoteSession> stale = sessionMapper.selectList(new LambdaQueryWrapper<RemoteSession>()
                .eq(RemoteSession::getStatus, RemoteSession.STATUS_INVITING)
                .lt(RemoteSession::getCreateTime, deadline));
        for (RemoteSession session : stale) {
            finishSession(session.getId(), "invite-timeout", 0L);
        }
    }

    /**
     * 审计流水分页。
     *
     * <p>排序键是 (createTime, id) 而不是 createTime：审计写入密集时同一秒可能落好几条，
     * 只按时间排序在 MySQL 里属于「排序键不唯一」，翻页会出现同一条跨页重复或整条被跳过。
     *
     * @param action 动作名模糊过滤，null/空串表示不过滤
     */
    public Page<RemoteAuditLog> auditPage(Long sessionId, long current, long size, String action) {
        RemoteSession session = requireOwned(sessionId);
        LambdaQueryWrapper<RemoteAuditLog> query = new LambdaQueryWrapper<RemoteAuditLog>()
                .eq(RemoteAuditLog::getSessionId, session.getId())
                .orderByDesc(RemoteAuditLog::getCreateTime)
                .orderByDesc(RemoteAuditLog::getId);
        if (!TextUtil.isBlank(action)) {
            query.like(RemoteAuditLog::getAction, action.trim());
        }
        return auditLogMapper.selectPage(new Page<>(current, size), query);
    }

    /**
     * 会话历史分页（展示视图）。
     *
     * <p>「我的历史」是双向的：既包含我发起的，也包含别人控我的，所以条件是两个用户列的 OR。
     * 补齐关联信息时全部走批量查询，一页 N 条只多花固定几次 SQL，与 N 无关。
     *
     * @param status 状态筛选（inviting/active/rejected/ended），null/空串表示不过滤
     */
    public Page<RemoteSessionVO> sessionPage(long current, long size, String status) {
        Long userId = SecurityUtil.getUserId();
        LambdaQueryWrapper<RemoteSession> query = new LambdaQueryWrapper<RemoteSession>()
                .and(w -> w.eq(RemoteSession::getInviterUserId, userId)
                        .or().eq(RemoteSession::getInviteeUserId, userId))
                .orderByDesc(RemoteSession::getCreateTime)
                .orderByDesc(RemoteSession::getId);
        if (!TextUtil.isBlank(status)) {
            query.eq(RemoteSession::getStatus, status.trim());
        }
        Page<RemoteSession> page = sessionMapper.selectPage(new Page<>(current, size), query);
        Page<RemoteSessionVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(toSessionViews(page.getRecords(), userId));
        return result;
    }

    /**
     * 会话实体 → 展示视图：批量补设备名、对端昵称、审计条数，并折算时长与平均码率。
     *
     * <p>设备名的归属优先级沿用 {@link #detail(Long)}：先按被控方 (inviteeUserId, deviceId) 配对，
     * 配不上退回控制方，再配不上就只显示 deviceId——设备行可能已被清理，认不出机器时
     * 机器指纹本身就是唯一线索，不能替换成占位符。
     */
    private List<RemoteSessionVO> toSessionViews(List<RemoteSession> records, Long userId) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Set<String> deviceIds = new HashSet<>();
        Set<Long> sessionIds = new HashSet<>();
        Set<Long> peerIds = new HashSet<>();
        for (RemoteSession session : records) {
            if (session.getDeviceId() != null) {
                deviceIds.add(session.getDeviceId());
            }
            sessionIds.add(session.getId());
            Long peer = peerOf(session, userId);
            if (peer != null && peer > 0) {
                peerIds.add(peer);
            }
        }

        // 两张名字表：byOwner 精确到 (userId, deviceId)，byDevice 只按设备指纹兜底
        Map<String, String> byOwner = new HashMap<>();
        Map<String, String> byDevice = new HashMap<>();
        for (RemoteDevice device : deviceService.listByDeviceIds(deviceIds)) {
            if (device.getDeviceName() == null) {
                continue;
            }
            byOwner.put(device.getUserId() + "|" + device.getDeviceId(), device.getDeviceName());
            byDevice.putIfAbsent(device.getDeviceId(), device.getDeviceName());
        }

        Map<Long, UserBriefDTO> peers = peerIds.isEmpty() || userQuerySpiProvider.getIfAvailable() == null
                ? Map.of() : userQuerySpiProvider.getObject().listByIds(peerIds);
        Map<Long, Long> auditCounts = auditLogMapper.countBySessionIds(sessionIds);

        List<RemoteSessionVO> views = new ArrayList<>(records.size());
        for (RemoteSession session : records) {
            String deviceName = byOwner.get(session.getInviteeUserId() + "|" + session.getDeviceId());
            if (deviceName == null) {
                deviceName = byOwner.get(session.getInviterUserId() + "|" + session.getDeviceId());
            }
            if (deviceName == null) {
                deviceName = byDevice.get(session.getDeviceId());
            }
            Long peerId = peerOf(session, userId);
            UserBriefDTO peer = peerId == null ? null : peers.get(peerId);

            // 进行中的会话库里 bytes 还是 0（收尾才落库），实时值从内存绑定取
            long bytes = session.getBytes() == null ? 0L : session.getBytes();
            if (RemoteSession.STATUS_ACTIVE.equals(session.getStatus())) {
                bytes = relayService().boundBytes(session.getId());
            }
            LocalDateTime end = session.getEndTime();
            long seconds = session.getStartTime() == null ? 0
                    : Duration.between(session.getStartTime(), end == null ? LocalDateTime.now() : end).getSeconds();
            Double bitrate = seconds > 0
                    ? Math.round(bytes * 8.0 / seconds / 1_000_000 * 100) / 100.0 : null;

            views.add(RemoteSessionVO.builder()
                    .id(session.getId())
                    .deviceId(session.getDeviceId())
                    .deviceName(deviceName == null ? session.getDeviceId() : deviceName)
                    .role(userId.equals(session.getInviterUserId())
                            ? RemoteSessionVO.ROLE_INVITER : RemoteSessionVO.ROLE_INVITEE)
                    .peerUserId(peerId)
                    .peerName(peer == null ? null : peer.getNickname())
                    .permission(session.getPermission())
                    .status(session.getStatus())
                    .startTime(session.getStartTime())
                    .endTime(end)
                    .durationSeconds(seconds)
                    .bytes(bytes)
                    .bitrateMbps(bitrate)
                    .endReason(session.getEndReason())
                    .auditCount(auditCounts.getOrDefault(session.getId(), 0L))
                    .createTime(session.getCreateTime())
                    .build());
        }
        return views;
    }

    /** 对端 = 不是我自己的那一方用户 ID；匿名识别码接入时 inviteeUserId 为 0 */
    private Long peerOf(RemoteSession session, Long userId) {
        return userId.equals(session.getInviterUserId())
                ? session.getInviteeUserId() : session.getInviterUserId();
    }

    /* ==================== 内部工具 ==================== */

    private RemoteSession requireOwned(Long sessionId) {
        RemoteSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.REMOTE_SESSION_NOT_FOUND);
        }
        Long userId = SecurityUtil.getUserId();
        if (!userId.equals(session.getInviterUserId()) && !userId.equals(session.getInviteeUserId())) {
            throw new BusinessException(ResultCode.REMOTE_SESSION_FORBIDDEN);
        }
        return session;
    }

    private RemoteSession requireActivePendingDevice(Long sessionId, AgentRegistry.AgentInfo agent) {
        RemoteSession session = sessionMapper.selectById(sessionId);
        if (session == null || !RemoteSession.STATUS_INVITING.equals(session.getStatus())) {
            throw new BusinessException(ResultCode.REMOTE_SESSION_NOT_FOUND);
        }
        // accept 只能由被控设备的连接发出：会话归属与连接身份必须一致
        if (!session.getInviteeUserId().equals(agent.getUserId())
                || !session.getDeviceId().equals(agent.getDeviceId())) {
            throw new BusinessException(ResultCode.REMOTE_SESSION_FORBIDDEN);
        }
        return session;
    }

    private void recordAudit(Long sessionId, String action, String detail, String actor) {
        String truncated = detail == null ? null
                : detail.length() > 1000 ? detail.substring(0, 1000) : detail;
        auditLogMapper.insert(RemoteAuditLog.builder()
                .sessionId(sessionId)
                .action(action)
                .detail(truncated)
                .actor(actor)
                .build());
    }

    /**
     * 会话结束原因 → 触发方。
     *
     * <p>只有「谁按了结束」才是人的行为：超时巡检、对端掉线、被新邀请顶替都是服务端
     * 自己收尾的结果，记成 system 而不是随便挑一方——审计里把系统行为安到某个用户头上，
     * 事后追责时是会把人冤枉的。
     */
    private static String actorOfReason(String reason) {
        if ("inviter-end".equals(reason)) {
            return RemoteAuditLog.ACTOR_INVITER;
        }
        if ("invitee-end".equals(reason) || "rejected".equals(reason)) {
            return RemoteAuditLog.ACTOR_INVITEE;
        }
        return RemoteAuditLog.ACTOR_SYSTEM;
    }

    private Long resolveSessionId(RemoteEnvelope env) {
        if (env.getSid() != null) {
            return env.getSid();
        }
        String value = env.getData() == null ? null : str(env.getData().get("sessionId"));
        if (value == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "sessionId");
        }
        return Long.valueOf(value);
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private byte[] randomKey() {
        byte[] key = new byte[32];
        secureRandom.nextBytes(key);
        return key;
    }

    private String randomTicket() {
        byte[] raw = new byte[24];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
