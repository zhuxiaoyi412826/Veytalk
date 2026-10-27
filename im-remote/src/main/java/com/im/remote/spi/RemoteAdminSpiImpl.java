package com.im.remote.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.domain.AdminRemoteAuditDTO;
import com.im.common.domain.AdminRemoteAuditQueryDTO;
import com.im.common.domain.AdminRemoteOverviewDTO;
import com.im.common.domain.AdminRemoteSessionDTO;
import com.im.common.domain.AdminRemoteSessionQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.AdminRemoteSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.remote.entity.RemoteAuditLog;
import com.im.remote.entity.RemoteDevice;
import com.im.remote.entity.RemoteSession;
import com.im.remote.manager.AgentRegistry;
import com.im.remote.mapper.RemoteAuditLogMapper;
import com.im.remote.mapper.RemoteDeviceMapper;
import com.im.remote.mapper.RemoteSessionMapper;
import com.im.remote.service.RemoteRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台远程控制审计契约的实现（{@link AdminRemoteSpi}）。
 *
 * <p><b>只读不写</b>：本类没有任何 UPDATE / DELETE，也不碰 {@code RemoteRelayService} 的
 * 绑定通道，全部取数都落在 {@code im_remote_session} 与 {@code im_remote_audit_log} 两张
 * 已有表上——这是这一批「零 DDL、纯读」承诺的落点，接口侧「为什么没有中断按钮」的
 * 三个理由写在 {@link AdminRemoteSpi} 的类注释里。
 *
 * <p>关联信息（设备名、两端昵称、审计条数）一律<b>批量补</b>：一页 N 行只多花固定几次查询。
 * 昵称走 {@code UserQuerySpi} 且用 {@code ObjectProvider} 取，与 {@code RemoteSessionService}
 * 同一个姿势——没装 im-user 的部署里这一屏照样能开，只是名字列成空。
 *
 * <p>进行中的会话流量取 {@code RemoteRelayService#binding} 的内存累计值而不是库里的
 * {@code bytes}：那个列只在收尾时落库一次（每帧都 UPDATE 会把中继热路径变成写数据库），
 * 所以会话还在进行时它必然是 0，照读会显示成「这场没传东西」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteAdminSpiImpl implements AdminRemoteSpi {

    /** 会话台账与单会话流水的单页上限，与视频 / 直播后台同口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 跨会话全局检索的单页上限，比会话内更严，理由见 {@link #pageAudits} */
    private static final long MAX_GLOBAL_AUDIT_PAGE_SIZE = 50;
    private static final int KEYWORD_MAX = 50;
    /** 设备名反查 deviceId 列表的行数上限：撞名机器再多也只取最近在线的这些 */
    private static final int DEVICE_NAME_HIT_LIMIT = 200;

    /**
     * 高危动作词表：能在<b>别人机器上</b>留下实际后果的那些。
     *
     * <p>词表的写侧在被控端 Agent（{@code FileOps} / {@code SystemOps} 的 sendAudit 调用），
     * 这里是读侧。之所以不把这份清单交给前端：那等于让一个工程改动要求另一个工程跟着改界面，
     * 漂移的后果是「新加的破坏动作在后台不标红」——审计里漏标比多标危险。
     *
     * <p>收进来的七个：{@code exec}（cmd 命令）、{@code ps-run}（起程序，等价于执行）、
     * {@code ps-kill}、{@code power}（关机 / 重启 / 锁屏）、{@code file-rm}、
     * {@code file-put}（往别人机器写文件）、{@code file-get}（从别人机器拿走文件，
     * 破坏性为零但隐私后果最重）。另外收 {@code danger-denied}：那是「想做但被 Agent
     * 本地开关挡下」的尝试，不收进来就分不清「没人试过」与「试了没成功」。
     *
     * <p>刻意<b>不收</b>的两类：{@code file-mkdir} / {@code file-rename} 是可逆的整理动作，
     * 收进来只会淹掉真信号；{@code clip-sync} 是隐私向而不是破坏向，且 Agent 只上报
     * {@code len=N}（不含内容），要看它得翻全量流水，不该占这个预置筛选。
     */
    private static final Set<String> DANGEROUS_ACTIONS = Set.of(
            "exec", "ps-run", "ps-kill", "power", "file-rm", "file-get", "file-put", "danger-denied");

    /** 状态机里「还没结束」的两个值，台账把它们顶到最前 */
    private static final String ORDER_LIVE_STATUS = "FIELD(status, 'active', 'inviting')";

    private final RemoteSessionMapper sessionMapper;
    private final RemoteAuditLogMapper auditLogMapper;
    private final RemoteDeviceMapper deviceMapper;
    private final AgentRegistry agentRegistry;
    private final RemoteRelayService relayService;
    private final ObjectProvider<UserQuerySpi> userQuerySpiProvider;

    @Override
    public PageResult<AdminRemoteSessionDTO> pageSessions(AdminRemoteSessionQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);

        QueryWrapper<RemoteSession> wrapper = new QueryWrapper<RemoteSession>()
                .eq(!TextUtil.isBlank(query.getStatus()), "status", trimToNull(query.getStatus()))
                // 进行中的排最前：管理员开这一页第一眼看的是「现在谁在控谁的机器」，
                // 这类行全库通常个位数。函数排序 Lambda 表达不了，只能用字符串列——
                // 串是本类私有常量，不来自任何外部参数，不存在注入面。
                .orderByAsc(ORDER_LIVE_STATUS)
                .orderByDesc("create_time")
                .orderByDesc("id");

        Long userId = query.getUserId();
        if (userId != null) {
            wrapper.and(w -> w.eq("inviter_user_id", userId).or().eq("invitee_user_id", userId));
        }
        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            if (keyword.length() > KEYWORD_MAX) {
                keyword = keyword.substring(0, KEYWORD_MAX);
            }
            // 会话表里只有 deviceId，设备名在 im_remote_device：先按名字反查指纹，
            // 再把两段条件并起来。反查限量 200 行按最近在线排序，见 DEVICE_NAME_HIT_LIMIT。
            List<String> nameHits = deviceIdsByNameLike(keyword);
            String kw = keyword;
            if (nameHits.isEmpty()) {
                wrapper.like("device_id", kw);
            } else {
                wrapper.and(w -> w.like("device_id", kw).or().in("device_id", nameHits));
            }
        }

        Page<RemoteSession> page = sessionMapper.selectPage(new Page<>(current, size), wrapper);
        List<RemoteSession> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        return PageResult.of(toSessionViews(records), page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminRemoteSessionDTO detail(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        RemoteSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return null;
        }
        List<AdminRemoteSessionDTO> views = toSessionViews(List.of(session));
        return views.isEmpty() ? null : views.get(0);
    }

    @Override
    public PageResult<AdminRemoteAuditDTO> pageAudits(AdminRemoteAuditQueryDTO query) {
        boolean global = query.getSessionId() == null;
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()),
                global ? MAX_GLOBAL_AUDIT_PAGE_SIZE : MAX_PAGE_SIZE);

        LambdaQueryWrapper<RemoteAuditLog> wrapper = new LambdaQueryWrapper<RemoteAuditLog>()
                .eq(!global, RemoteAuditLog::getSessionId, query.getSessionId())
                .eq(!TextUtil.isBlank(query.getActor()), RemoteAuditLog::getActor, trimToNull(query.getActor()));
        if (Boolean.TRUE.equals(query.getDangerOnly())) {
            wrapper.in(RemoteAuditLog::getAction, DANGEROUS_ACTIONS);
        } else {
            String action = trimToNull(query.getAction());
            wrapper.eq(action != null, RemoteAuditLog::getAction, action);
        }
        String keyword = trimToNull(query.getKeyword());
        if (keyword != null && keyword.length() > KEYWORD_MAX) {
            keyword = keyword.substring(0, KEYWORD_MAX);
        }
        wrapper.like(keyword != null, RemoteAuditLog::getDetail, keyword);

        if (global) {
            // 全局检索按主键倒序而不是 create_time：雪花 ID 本身随时间递增，倒着走主键就能
            // 「边扫边停」取满一页，按 create_time 排则要先把命中行全捞出来做 filesort。
            // 分页仍要付一次 COUNT(*)（这是本页真正贵的地方），代价靠「上限 50 + 页面不轮询」
            // 夹住；审计量长起来以后的解法是补 idx_action_time，属于未来 DDL，本批不做。
            wrapper.orderByDesc(RemoteAuditLog::getId);
        } else {
            wrapper.orderByDesc(RemoteAuditLog::getCreateTime).orderByDesc(RemoteAuditLog::getId);
        }

        Page<RemoteAuditLog> page = auditLogMapper.selectPage(new Page<>(current, size), wrapper);
        List<RemoteAuditLog> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        return PageResult.of(toAuditViews(records), page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminRemoteOverviewDTO overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long onlineAgents = agentRegistry.snapshot().stream()
                .filter(info -> info.isAuthed() && info.getSession().isOpen())
                .count();
        return AdminRemoteOverviewDTO.builder()
                .activeSessions(countByStatus(RemoteSession.STATUS_ACTIVE))
                .invitingSessions(countByStatus(RemoteSession.STATUS_INVITING))
                .todaySessions(sessionMapper.selectCount(new LambdaQueryWrapper<RemoteSession>()
                        .ge(RemoteSession::getCreateTime, todayStart)))
                .onlineAgents((int) onlineAgents)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /* ==================== 组装 ==================== */

    private long countByStatus(String status) {
        return sessionMapper.selectCount(new LambdaQueryWrapper<RemoteSession>()
                .eq(RemoteSession::getStatus, status));
    }

    /**
     * 会话实体 → 后台档案：批量补设备行、两端昵称、审计条数，并折算时长 / 码率 / 两个实时态。
     *
     * <p>设备名配对优先级沿用 {@code RemoteSessionService#toSessionViews}：先按被控方
     * (inviteeUserId, deviceId) 精确配，配不上退回控制方，再配不上只显示 deviceId——
     * 同一台机器换账号登录会在设备表里留下多行，以归属人为准才不会把名字安错机器。
     */
    private List<AdminRemoteSessionDTO> toSessionViews(List<RemoteSession> records) {
        Set<String> deviceIds = new HashSet<>();
        Set<Long> sessionIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (RemoteSession session : records) {
            if (session.getDeviceId() != null) {
                deviceIds.add(session.getDeviceId());
            }
            sessionIds.add(session.getId());
            addIfAccount(session.getInviterUserId(), userIds);
            addIfAccount(session.getInviteeUserId(), userIds);
        }
        Map<String, String> byOwner = new HashMap<>();
        Map<String, RemoteDevice> byDevice = new HashMap<>();
        for (RemoteDevice device : deviceMapper.selectList(new LambdaQueryWrapper<RemoteDevice>()
                .in(RemoteDevice::getDeviceId, deviceIds))) {
            byOwner.put(device.getUserId() + "|" + device.getDeviceId(), device.getDeviceName());
            byDevice.putIfAbsent(device.getDeviceId(), device);
        }
        NameResolver names = namesOf(userIds);
        Map<Long, Long> auditCounts = auditLogMapper.countBySessionIds(sessionIds);

        List<AdminRemoteSessionDTO> views = new ArrayList<>(records.size());
        for (RemoteSession session : records) {
            String deviceName = byOwner.get(session.getInviteeUserId() + "|" + session.getDeviceId());
            if (deviceName == null) {
                deviceName = byOwner.get(session.getInviterUserId() + "|" + session.getDeviceId());
            }
            if (deviceName == null) {
                RemoteDevice fallback = byDevice.get(session.getDeviceId());
                deviceName = fallback == null ? null : fallback.getDeviceName();
            }
            RemoteDevice deviceRow = byDevice.get(session.getDeviceId());
            views.add(toView(session, deviceName, deviceRow == null ? null : deviceRow.getOs(), names,
                    auditCounts.getOrDefault(session.getId(), 0L)));
        }
        return views;
    }

    private AdminRemoteSessionDTO toView(RemoteSession session, String deviceName, String deviceOs,
                                         NameResolver names, long auditCount) {
        Long inviter = session.getInviterUserId();
        Long invitee = session.getInviteeUserId();
        boolean ongoing = RemoteSession.STATUS_ACTIVE.equals(session.getStatus());

        long bytes = session.getBytes() == null ? 0L : session.getBytes();
        RemoteRelayService.Binding binding = ongoing ? relayService.binding(session.getId()) : null;
        if (ongoing && binding != null) {
            bytes = binding.grandTotalBytes();
        }
        LocalDateTime end = session.getEndTime();
        long seconds = session.getStartTime() == null ? 0
                : Duration.between(session.getStartTime(), end == null ? LocalDateTime.now() : end).getSeconds();
        Double bitrate = seconds > 0
                ? Math.round(bytes * 8.0 / seconds / 1_000_000 * 100) / 100.0 : null;

        return AdminRemoteSessionDTO.builder()
                .sessionId(session.getId())
                .deviceId(session.getDeviceId())
                .deviceName(TextUtil.isBlank(deviceName) ? session.getDeviceId() : deviceName)
                .deviceOs(deviceOs)
                .inviterUserId(inviter)
                .inviterName(names.nameOf(inviter))
                .inviteeUserId(invitee)
                .inviteeName(names.nameOf(invitee))
                .permission(session.getPermission())
                .status(session.getStatus())
                .createTime(session.getCreateTime())
                .startTime(session.getStartTime())
                .endTime(end)
                .endReason(session.getEndReason())
                .durationSeconds(seconds)
                .bytes(bytes)
                .bitrateMbps(bitrate)
                .auditCount(auditCount)
                // 此刻真相：Agent 连接在不在、控制端凭票接入的绑定在不在。两个值都来自
                // 服务端内存，多实例部署下只反映当前实例，口径与看板的「仅当前实例」一致。
                .agentOnline(agentRegistry.get(invitee, session.getDeviceId()) != null)
                .relaying(binding != null)
                .directPath(binding == null ? null : binding.directPath())
                .build();
    }

    /**
     * 流水行 → 后台视图：批量补「这条属于哪一场、谁控谁、哪台机器」。
     *
     * <p>全局检索时这四列不是锦上添花而是必需——孤零零一句「exec: shutdown」无法处置，
     * 管理员必须先知道它发生在谁的哪台机器上。
     */
    private List<AdminRemoteAuditDTO> toAuditViews(List<RemoteAuditLog> records) {
        Set<Long> sessionIds = new HashSet<>();
        for (RemoteAuditLog row : records) {
            if (row.getSessionId() != null) {
                sessionIds.add(row.getSessionId());
            }
        }
        Map<Long, RemoteSession> sessions = new HashMap<>();
        Set<String> deviceIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        if (!sessionIds.isEmpty()) {
            for (RemoteSession session : sessionMapper.selectList(new LambdaQueryWrapper<RemoteSession>()
                    .in(RemoteSession::getId, sessionIds))) {
                sessions.put(session.getId(), session);
                if (session.getDeviceId() != null) {
                    deviceIds.add(session.getDeviceId());
                }
                addIfAccount(session.getInviterUserId(), userIds);
                addIfAccount(session.getInviteeUserId(), userIds);
            }
        }
        Map<String, String> deviceNames = new HashMap<>();
        if (!deviceIds.isEmpty()) {
            for (RemoteDevice device : deviceMapper.selectList(new LambdaQueryWrapper<RemoteDevice>()
                    .in(RemoteDevice::getDeviceId, deviceIds))) {
                deviceNames.putIfAbsent(device.getDeviceId(), device.getDeviceName());
            }
        }
        NameResolver names = namesOf(userIds);

        List<AdminRemoteAuditDTO> views = new ArrayList<>(records.size());
        for (RemoteAuditLog row : records) {
            RemoteSession session = sessions.get(row.getSessionId());
            String deviceName = session == null ? null : deviceNames.get(session.getDeviceId());
            views.add(AdminRemoteAuditDTO.builder()
                    .logId(row.getId())
                    .sessionId(row.getSessionId())
                    .action(row.getAction())
                    .detail(row.getDetail())
                    .actor(row.getActor())
                    .dangerous(row.getAction() != null && DANGEROUS_ACTIONS.contains(row.getAction()))
                    .createTime(row.getCreateTime())
                    .inviterName(session == null ? null : names.nameOf(session.getInviterUserId()))
                    .inviteeName(session == null ? null : names.nameOf(session.getInviteeUserId()))
                    .deviceName(session == null ? null
                            : (TextUtil.isBlank(deviceName) ? session.getDeviceId() : deviceName))
                    .permission(session == null ? null : session.getPermission())
                    .build());
        }
        return views;
    }

    /* ==================== 内部工具 ==================== */

    private List<String> deviceIdsByNameLike(String keyword) {
        List<RemoteDevice> hits = deviceMapper.selectList(new LambdaQueryWrapper<RemoteDevice>()
                .select(RemoteDevice::getDeviceId)
                .like(RemoteDevice::getDeviceName, keyword)
                .orderByDesc(RemoteDevice::getLastOnlineTime)
                .last("LIMIT " + DEVICE_NAME_HIT_LIMIT));
        List<String> ids = new ArrayList<>(hits.size());
        for (RemoteDevice device : hits) {
            if (device.getDeviceId() != null && !ids.contains(device.getDeviceId())) {
                ids.add(device.getDeviceId());
            }
        }
        return ids;
    }

    /** 识别码模式下 inviteeUserId=0，那不是账号，不能拿去查用户表 */
    private static void addIfAccount(Long userId, Collection<Long> sink) {
        if (userId != null && userId > 0) {
            sink.add(userId);
        }
    }

    /**
     * 取一份昵称解析器。im-user 缺席时 {@code available=false}，此时所有名字列留空而不是
     * 填「已注销用户」——把「查不到资料」和「这个账号确实没了」混成一句话，
     * 会让管理员对着一个根本没装用户模块的部署追根本不存在的注销记录。
     */
    private NameResolver namesOf(Set<Long> userIds) {
        UserQuerySpi spi = userQuerySpiProvider.getIfAvailable();
        if (spi == null) {
            return new NameResolver(false, Map.of());
        }
        return new NameResolver(true, userIds.isEmpty() ? Map.of() : spi.listByIds(userIds));
    }

    private record NameResolver(boolean available, Map<Long, UserBriefDTO> briefs) {

        String nameOf(Long userId) {
            if (userId == null || userId <= 0) {
                return null;
            }
            if (!available) {
                return null;
            }
            UserBriefDTO brief = briefs.get(userId);
            if (brief == null) {
                return "已注销用户";
            }
            return TextUtil.isNotBlank(brief.getNickname()) ? brief.getNickname() : brief.getUsername();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
