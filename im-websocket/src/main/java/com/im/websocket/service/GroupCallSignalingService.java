package com.im.websocket.service;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.WsPacket;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.GroupSpi;
import com.im.common.spi.PushSpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import com.im.websocket.dto.WsGroupCallPayload;
import com.im.websocket.manager.WsConnection;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 群通话信令编排：在一群群成员之间转发 WebRTC 协商，并维护一场多人通话的花名册。
 *
 * <h2>媒体是 mesh，不是 SFU</h2>
 *
 * <p>本服务一帧媒体都不碰，而且<b>连转发都不需要</b>：N 个成员两两直连，每人自己开 {@code N-1} 路
 * {@code RTCPeerConnection}，媒体走 P2P（同网段时是 host 候选直连，跨 NAT 才落到 TURN 中继）。
 * 服务端搬运的只有 SDP 与 ICE 候选，按 {@code toUserId} 精确投递到那一个人。
 * 因此人数上限被死死约束在 {@code im.call.group-max-slots}（默认 4、硬夹到 6）：
 * 上行路数随人数线性涨，第 7 个人的视频上行必然把手机和家用宽带的上行打穿。
 * 真要做十几人会议得引 SFU（单独立项），那不是本服务的量级改动。
 *
 * <h2>协商方向由「谁后进房」决定，不靠 perfect negotiation</h2>
 *
 * <p>mesh 里两条相邻的 PeerConnection 若同时发 offer 会互相冲撞。规则只有一句：
 * <b>后进房的人对每一个先到者发起 offer</b>，先到者只回 answer。这条规则能成立，
 * 是因为 join 的处理在 Redis 锁里（见 {@link #withState}）——花名册顺序是服务端定的，
 * 每个客户端都只按「我拿到 roster 时谁已经在了」来决定自己是不是 caller，两端不会得出相反结论。
 *
 * <h2>身份只认连接，不认报文</h2>
 *
 * <p>下行 {@code fromUserId} 一律按 {@link WsConnection#userId()} 覆写；成员资格每帧都回查
 * {@link GroupSpi#isMember}（被移出群的人下一帧就被拒），不采信客户端自称的名单。
 *
 * <h2>实时态放 Redis，与 1:1 通话、连麦同源</h2>
 *
 * <p>{@code callId → 花名册}、{@code userId → 忙线指针}、{@code groupId → 本群当前通话} 都带 TTL：
 * 进程重启、成员关掉页面不发 leave，都靠 TTL 与定时清扫收敛。三者互不互斥（同一人理论上可以
 * 同时在 1:1 通话与群通话里），这是<b>已知取舍</b>而非疏忽：三套忙线键分属三个服务，
 * 要真正互斥得把它们收敛成一个「媒体会话注册表」，那是一次跨模块重构，已记入待办。
 *
 * <p>多实例部署下 {@link PushSpi} 只认本进程连接表，跨节点扇出与消息推送是同一道待办。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupCallSignalingService {

    /** 通话态主键：callId → 花名册 */
    private static final String STATE_PREFIX = "im:gcall:state:";
    /** 忙线指针：userId → callId，一人同时只允许一路群通话 */
    private static final String USER_PREFIX = "im:gcall:user:";
    /** 群维度索引：groupId → callId，一是「一群同时只一路」的闸门，二是 REST 查活跃通话的入口 */
    private static final String GROUP_PREFIX = "im:gcall:group:";
    /** 读改写互斥锁：花名册是一个 JSON 整体，两人同时进房必须排队，否则后写的会抹掉前一个 */
    private static final String LOCK_PREFIX = "im:gcall:lock:";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String MEDIA_VIDEO = "video";

    /** 锁最长等待：8 次 × 50ms。超了就直接改（宁可退化成后写覆盖，也不能让信令线程无限期挂住） */
    private static final int LOCK_RETRY = 8;
    private static final long LOCK_RETRY_MS = 50L;
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private final RedisUtil redisUtil;
    private final PushSpi pushSpi;
    private final JsonUtil jsonUtil;
    private final ImProperties imProperties;

    /** 群成员资格的唯一依据；群组模块缺席时群通话整体不可用（与连麦缺 LiveRoomSpi 时的处置一致） */
    private final ObjectProvider<GroupSpi> groupSpiProvider;

    /**
     * 处理一帧上行 {@code groupcall} 信令。异常交由 {@code WsInboundDispatcher} 统一翻译成 error 帧、
     * 不断连；所以这里只把「协议级非法」（缺 callId/groupId、未知 action、报文解析失败）当异常抛出，
     * 业务级拒绝（忙线 / 已满 / 非群成员 / 已结束）一律走 {@code groupcall-signal} 的 failed 回帧。
     */
    public void handle(WsConnection connection, WsPacket packet) {
        ImProperties.Call call = imProperties.getCall();
        if (!call.isEnabled() || !call.isGroupEnabled()) {
            // 功能关闭时静默丢弃：前端本就应禁用入口，这里是兜底
            return;
        }
        WsGroupCallPayload payload = jsonUtil.convert(packet.getData(), WsGroupCallPayload.class);
        if (payload == null || TextUtil.isBlank(payload.getAction())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "groupcall 报文载荷无法解析");
        }
        Long me = connection.userId();
        String deviceId = connection.deviceId();
        switch (payload.getAction()) {
            case "create" -> handleCreate(me, deviceId, payload);
            case "invite" -> handleInvite(me, deviceId, payload);
            case "join" -> handleJoin(me, deviceId, payload);
            case "reject" -> handleReject(me, deviceId, payload);
            case "leave" -> handleLeave(me, deviceId, payload);
            case "offer", "answer", "ice" -> handleRelay(me, deviceId, payload);
            case "state" -> handleState(me, deviceId, payload);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知的 groupcall action: " + payload.getAction());
        }
    }

    /* ==================== create：发起人开一场并邀请成员 ==================== */

    private void handleCreate(Long me, String deviceId, WsGroupCallPayload payload) {
        Long groupId = payload.getGroupId();
        if (groupId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "create 缺少 groupId");
        }
        if (TextUtil.isBlank(payload.getCallId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "create 缺少 callId");
        }
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null) {
            throw new BusinessException(ResultCode.GROUP_CALL_DISABLED);
        }
        if (!groupSpi.isMember(groupId, me)) {
            throw new BusinessException(ResultCode.GROUP_CALL_NOT_MEMBER);
        }
        int maxSlots = maxSlots();
        // 一个群同时只一路：否则两个人各开一路，两边各自对着不同的名单协商，观众看到的是「明明在通话却没人」
        if (redisUtil.hasKey(GROUP_PREFIX + groupId)) {
            signalFailed(me, deviceId, payload.getCallId(), groupId, "exists");
            return;
        }
        if (redisUtil.hasKey(USER_PREFIX + me)) {
            signalFailed(me, deviceId, payload.getCallId(), groupId, "self-busy");
            return;
        }

        GroupCallState state = new GroupCallState();
        state.setCallId(payload.getCallId());
        state.setGroupId(groupId);
        state.setCreatorUserId(me);
        state.setMediaType(MEDIA_VIDEO.equals(payload.getMediaType()) ? MEDIA_VIDEO : "audio");
        state.setCreatedAt(System.currentTimeMillis());
        state.getMembers().add(activeMember(me, deviceId, state.getMediaType()));

        // 邀请名单：去重、去掉自己、去掉非成员，并夹到「上限 - 发起人」，超出部分直接丢弃而不是报错
        Set<Long> invitees = new LinkedHashSet<>();
        if (payload.getCalleeUserIds() != null) {
            for (Long id : payload.getCalleeUserIds()) {
                if (id == null || id.equals(me) || invitees.contains(id)) {
                    continue;
                }
                if (state.getMembers().size() >= maxSlots) {
                    break;
                }
                if (!groupSpi.isMember(groupId, id)) {
                    continue;
                }
                WsGroupCallPayload.GroupCallMember pending = new WsGroupCallPayload.GroupCallMember();
                pending.setUserId(id);
                pending.setStatus(STATUS_PENDING);
                pending.setJoinedAt(System.currentTimeMillis());
                state.getMembers().add(pending);
                invitees.add(id);
            }
        }
        redisUtil.setObject(STATE_PREFIX + state.getCallId(), state, stateTtl());
        redisUtil.set(USER_PREFIX + me, state.getCallId(), stateTtl());
        redisUtil.set(GROUP_PREFIX + groupId, state.getCallId(), stateTtl());

        if (invitees.isEmpty()) {
            return;
        }
        // 逐个推 invite 到被邀请人的全部设备（还没定 deviceId，故按用户推）；
        // 推不动（不在线）不在这儿剔除——留成 PENDING 由振铃超时统一收，省掉一次读改写，
        // 代价是发起人要等 ring-timeout（默认 45 秒）才看到「无应答」而不是「不在线」
        WsGroupCallPayload out = frame("invite", state, me);
        out.setMediaType(state.getMediaType());
        for (Long invitee : invitees) {
            pushSpi.pushToUser(invitee, packet(out));
        }
    }

    /* ==================== invite：通话进行中新邀约 ==================== */

    /**
     * 往已在的房间里追加邀约：房里的任意 ACTIVE 成员都能拉人，不必是发起人
     * ——发起人可能已经挂断，而通话还在继续。
     *
     * <p>与 {@code create} 的区别只是「建房 vs 往已有花名册加 PENDING 条目」：群索引、TTL、
     * 协商规则都不动，被邀人接听走的仍是 {@code join} 那条路，所以服务端无需记「谁邀了谁」。
     * 满了不报错而是默默截断，与 create 保持同一个口径。
     */
    private void handleInvite(Long me, String deviceId, WsGroupCallPayload payload) {
        GroupCallState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(me, deviceId, payload.getCallId(), payload.getGroupId(), "gone");
            return;
        }
        if (!isActiveMember(state, me)) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "forbidden");
            return;
        }
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null) {
            throw new BusinessException(ResultCode.GROUP_CALL_DISABLED);
        }
        int maxSlots = maxSlots();
        if (state.getMembers().size() >= maxSlots) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "full");
            return;
        }
        List<Long> added = new ArrayList<>();
        withState(state.getCallId(), fresh -> {
            if (payload.getCalleeUserIds() == null) {
                return List.of();
            }
            for (Long id : payload.getCalleeUserIds()) {
                // 已在名单里的（PENDING 也算）不重复邀，免得同一个人在界面上出现两个「等待接听」
                if (id == null || member(fresh, id) != null || fresh.getMembers().size() >= maxSlots) {
                    continue;
                }
                if (!groupSpi.isMember(fresh.getGroupId(), id)) {
                    continue;
                }
                WsGroupCallPayload.GroupCallMember pending = new WsGroupCallPayload.GroupCallMember();
                pending.setUserId(id);
                pending.setStatus(STATUS_PENDING);
                pending.setJoinedAt(System.currentTimeMillis());
                fresh.getMembers().add(pending);
                added.add(id);
            }
            return List.of();
        });
        if (added.isEmpty()) {
            return;
        }
        GroupCallState after = load(state.getCallId());
        if (after == null) {
            return;
        }
        // fromUserId 是邀请人而不是发起人：被邀那一端要显示「张三 邀请你加入群通话」
        WsGroupCallPayload out = frame("invite", after, me);
        for (Long invitee : added) {
            pushSpi.pushToUser(invitee, packet(out));
        }
    }

    /* ==================== join：接听邀请，或直接加入群里已开的通话 ==================== */

    private void handleJoin(Long me, String deviceId, WsGroupCallPayload payload) {
        GroupCallState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(me, deviceId, payload.getCallId(), payload.getGroupId(), "gone");
            return;
        }
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null || !groupSpi.isMember(state.getGroupId(), me)) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "not-member");
            return;
        }
        int maxSlots = maxSlots();
        // 忙线：本人在另一路群通话里（换设备进房时指针可能指向的就是本场，故比对值而不是只看存在性）
        String busy = redisUtil.get(USER_PREFIX + me);
        if (busy != null && !busy.equals(state.getCallId())) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "self-busy");
            return;
        }

        List<Long> notifyTargets = withState(state.getCallId(), fresh -> {
            if (member(fresh, me) != null) {
                // 已在名单里（重复 join / 换设备重进）：把状态钉成 ACTIVE 并改指到新设备，其余人无需再收 member-join
                WsGroupCallPayload.GroupCallMember self = member(fresh, me);
                self.setStatus(STATUS_ACTIVE);
                self.setDeviceId(deviceId);
                self.setJoinedAt(System.currentTimeMillis());
                return List.of();
            }
            if (fresh.getMembers().size() >= maxSlots) {
                return null;
            }
            fresh.getMembers().add(activeMember(me, deviceId, fresh.getMediaType()));
            // 通知所有先到者：多了一个人要为 TA 建 PeerConnection（PENDING 的人不需要，TA 还没进房）
            List<Long> targets = new ArrayList<>();
            for (WsGroupCallPayload.GroupCallMember m : fresh.getMembers()) {
                if (STATUS_ACTIVE.equals(m.getStatus()) && !me.equals(m.getUserId())) {
                    targets.add(m.getUserId());
                }
            }
            return targets;
        });
        if (notifyTargets == null) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "full");
            return;
        }
        redisUtil.set(USER_PREFIX + me, state.getCallId(), stateTtl());

        // 等锁期间可能已经排进了别人的 join，下行帧一律以写回后的花名册为准，
        // 不能用进锁前读到的旧副本——那会漏掉同时进房的人，新来者不知道该向谁发起 offer
        GroupCallState after = load(state.getCallId());
        if (after == null) {
            return;
        }

        // 给新人一份花名册：它据此对每个 ACTIVE 成员发起 offer（后进房者恒为 caller）
        WsGroupCallPayload roster = frame("roster", after, me);
        roster.setMembers(activeOthers(after, me));
        pushToDevice(me, deviceId, roster);

        if (!notifyTargets.isEmpty()) {
            WsGroupCallPayload joinFrame = frame("member-join", after, me);
            WsGroupCallPayload.GroupCallMember self = member(after, me);
            joinFrame.setMembers(self == null ? List.of() : List.of(copy(self)));
            for (Long target : notifyTargets) {
                pushToMember(after, target, joinFrame);
            }
        }
    }

    /* ==================== reject：被邀请人明确拒绝 ==================== */

    private void handleReject(Long me, String deviceId, WsGroupCallPayload payload) {
        GroupCallState state = load(payload.getCallId());
        if (state == null || member(state, me) == null) {
            // 不在名单里的人发 reject（迟到的、或界面上点了两次）：静默忽略，不打扰任何人
            return;
        }
        List<Long> notifyTargets = withState(state.getCallId(), fresh -> {
            if (fresh.getMembers().removeIf(m -> me.equals(m.getUserId()))) {
                // 只告诉发起人「这人拒了」：其余人压根还不知道有这个人被邀（PENDING 不进 roster）
                return List.of(fresh.getCreatorUserId());
            }
            return List.of();
        });
        redisUtil.delete(USER_PREFIX + me);
        // 同上：以写回后的花名册取「发起人的 deviceId」，否则可能推给他没接听的那台设备
        GroupCallState after = load(state.getCallId());
        if (after != null) {
            WsGroupCallPayload out = frame("member-leave", after, me);
            out.setReason(TextUtil.isBlank(payload.getReason()) ? "reject" : payload.getReason());
            for (Long target : notifyTargets) {
                pushToMember(after, target, out);
            }
        }
        closeIfEmpty(state.getCallId(), state.getGroupId());
    }

    /* ==================== leave：挂断 / 关页面 ==================== */

    private void handleLeave(Long me, String deviceId, WsGroupCallPayload payload) {
        GroupCallState state = load(payload.getCallId());
        if (state == null) {
            return;
        }
        if (member(state, me) == null) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "forbidden");
            return;
        }
        List<Long> notifyTargets = withState(state.getCallId(), fresh -> {
            fresh.getMembers().removeIf(m -> me.equals(m.getUserId()));
            List<Long> targets = new ArrayList<>();
            for (WsGroupCallPayload.GroupCallMember m : fresh.getMembers()) {
                if (STATUS_ACTIVE.equals(m.getStatus())) {
                    targets.add(m.getUserId());
                }
            }
            return targets;
        });
        redisUtil.delete(USER_PREFIX + me);
        GroupCallState after = load(state.getCallId());
        if (after != null) {
            WsGroupCallPayload out = frame("member-leave", after, me);
            out.setReason(TextUtil.isBlank(payload.getReason()) ? "user-leave" : payload.getReason());
            for (Long target : notifyTargets) {
                pushToMember(after, target, out);
            }
        }
        closeIfEmpty(state.getCallId(), state.getGroupId());
    }

    /* ==================== offer / answer / ice：按 toUserId 精确中继 ==================== */

    private void handleRelay(Long me, String deviceId, WsGroupCallPayload payload) {
        GroupCallState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(me, deviceId, payload.getCallId(), payload.getGroupId(), "gone");
            return;
        }
        Long to = payload.getToUserId();
        if (to == null || to.equals(me)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "中继帧的 toUserId 非法");
        }
        if (!isActiveMember(state, me) || !isActiveMember(state, to)) {
            signalFailed(me, deviceId, state.getCallId(), state.getGroupId(), "forbidden");
            return;
        }
        WsGroupCallPayload out = frame(payload.getAction(), state, me);
        out.setToUserId(to);
        out.setSdpType(payload.getSdpType());
        out.setSdp(payload.getSdp());
        out.setCandidate(payload.getCandidate());
        pushToDevice(to, deviceIdOf(state, to), out);
        // 活跃通话中每次协商续一次 TTL，让 maxDuration 从「进房后」起算而不是从 create 起算
        redisUtil.expire(STATE_PREFIX + state.getCallId(), stateTtl());
    }

    /* ==================== state：静音 / 摄像头变更，广播给其余人 ==================== */

    private void handleState(Long me, String deviceId, WsGroupCallPayload payload) {
        GroupCallState state = load(payload.getCallId());
        if (state == null || !isActiveMember(state, me)) {
            return;
        }
        withState(state.getCallId(), fresh -> {
            WsGroupCallPayload.GroupCallMember self = member(fresh, me);
            if (self != null) {
                if (payload.getMuted() != null) {
                    self.setMuted(payload.getMuted());
                }
                if (payload.getCameraOff() != null) {
                    self.setCameraOff(payload.getCameraOff());
                }
            }
            return List.of();
        });
        WsGroupCallPayload out = frame("state", state, me);
        out.setMuted(payload.getMuted());
        out.setCameraOff(payload.getCameraOff());
        for (WsGroupCallPayload.GroupCallMember m : state.getMembers()) {
            if (STATUS_ACTIVE.equals(m.getStatus()) && !me.equals(m.getUserId())) {
                pushToDevice(m.getUserId(), m.getDeviceId(), out);
            }
        }
    }

    /* ==================== REST：本群当前进行中的通话 ==================== */

    /**
     * 查本群当前是否有进行中的群通话（前端进群聊时查一次，之后靠 member-join / end 帧增量维护）。
     *
     * <p>没有走 WebSocket 广播「本群开了一路通话」：那要求服务端认识「群」这个概念并往全群连接扇一帧，
     * 而 im-websocket 不持有成员名单（星型约束），一次全群扇出的成本远高于每人进群聊时查一次 Redis。
     *
     * <p>成员资格在这里也要回查：这个端点只验登录不验群籍，不拦就等于把「哪个群正在开通话、
     * 里面有哪些人」变成一个全平台可查的旁路。
     *
     * @return 无进行中通话时返回 {@code null}
     * @throws BusinessException 群组模块缺席或非本群成员
     */
    public WsGroupCallPayload activeCallOf(Long groupId, Long viewerId) {
        if (groupId == null) {
            return null;
        }
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null) {
            throw new BusinessException(ResultCode.GROUP_CALL_DISABLED);
        }
        if (!groupSpi.isMember(groupId, viewerId)) {
            throw new BusinessException(ResultCode.GROUP_CALL_NOT_MEMBER);
        }
        String callId = redisUtil.get(GROUP_PREFIX + groupId);
        if (callId == null) {
            return null;
        }
        GroupCallState state = load(callId);
        if (state == null) {
            // 索引残留（进程被 kill -9 之类），顺手清掉，别让这一群永久「以为有人在通话」
            redisUtil.delete(GROUP_PREFIX + groupId);
            return null;
        }
        WsGroupCallPayload vo = frame("roster", state, viewerId);
        vo.setMembers(activeOthers(state, null));
        return vo;
    }

    /* ==================== 定时清扫：邀请无应答、通话超长、空房 ==================== */

    /**
     * 每 5 秒扫一遍进行中的群通话。必须有这一趟：超时是「没有任何信令进来」时才发生的事件，
     * 帧驱动永远等不到触发点；TTL 能让 Redis 自己过期，但过期同时要给在线其余人推一帧，
     * 否则界面上会一直留着「等待接听」或一个已经没人看的通话条。
     */
    @Scheduled(fixedDelay = 5000)
    public void sweepExpired() {
        ImProperties.Call call = imProperties.getCall();
        if (!call.isEnabled() || !call.isGroupEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long ringMs = call.getRingTimeoutSeconds() * 1000L;
        long maxMs = call.getMaxDurationSeconds() * 1000L;
        for (String key : redisUtil.keys(STATE_PREFIX + "*")) {
            GroupCallState state = redisUtil.getObject(key, GroupCallState.class);
            if (state == null) {
                continue;
            }
            // 1) 整场超长：向全员下发 end 并清理
            if (now - state.getCreatedAt() >= maxMs) {
                terminate(state, "max-duration");
                continue;
            }
            // 2) 邀请无应答：摘掉这些 PENDING 条目，并告诉发起人
            List<Long> timedOut = new ArrayList<>();
            for (WsGroupCallPayload.GroupCallMember m : state.getMembers()) {
                if (STATUS_PENDING.equals(m.getStatus()) && now - m.getJoinedAt() >= ringMs) {
                    timedOut.add(m.getUserId());
                }
            }
            if (!timedOut.isEmpty()) {
                withState(state.getCallId(), fresh -> {
                    fresh.getMembers().removeIf(m -> STATUS_PENDING.equals(m.getStatus())
                            && now - m.getJoinedAt() >= ringMs);
                    return List.of();
                });
                // 无应答的人借 fromUserId 表明「离场的是 TA」，与 reject / leave 同一个形状，前端不必分两套解析；
                // 只报给发起人，其余成员从未见过这些 PENDING 条目
                for (Long gone : timedOut) {
                    WsGroupCallPayload out = frame("member-leave", state, gone);
                    out.setReason("timeout");
                    pushToDevice(state.getCreatorUserId(), deviceIdOf(state, state.getCreatorUserId()), out);
                }
            }
            // 3) 人走空了：直接收房
            closeIfEmpty(state.getCallId(), state.getGroupId());
        }
    }

    private void terminate(GroupCallState state, String reason) {
        WsGroupCallPayload out = frame("end", state, null);
        out.setReason(reason);
        for (WsGroupCallPayload.GroupCallMember m : state.getMembers()) {
            pushToDevice(m.getUserId(), m.getDeviceId(), out);
        }
        redisUtil.delete(STATE_PREFIX + state.getCallId());
        redisUtil.delete(GROUP_PREFIX + state.getGroupId());
        for (WsGroupCallPayload.GroupCallMember m : state.getMembers()) {
            redisUtil.delete(USER_PREFIX + m.getUserId());
        }
    }

    /** 名单空了就收房：删状态、删群索引。此刻已无人可通知——最后一个人自己就在 leave 的途中，界面上靠本地状态自收 */
    private void closeIfEmpty(String callId, Long groupId) {
        GroupCallState state = load(callId);
        if (state == null || !state.getMembers().isEmpty()) {
            return;
        }
        redisUtil.delete(STATE_PREFIX + callId);
        redisUtil.delete(GROUP_PREFIX + groupId);
        log.debug("[群通话] {} 成员已走空，收房", callId);
    }

    /* ==================== 花名册读写 ==================== */

    /**
     * 在短锁里读改写花名册。
     *
     * <p>为什么非要锁：花名册是一整个 JSON，读出来改再写回，两个人同时进房就是后写覆盖前写
     * ——表现是「B 明明进了，A 的界面上没有 B」。{@link RedisUtil#setIfAbsent} 提供 SETNX，
     * 拿到锁的人串行改，拿不到就重试。
     *
     * <p>拿不到锁时<b>照常执行</b>并打一条 warn：宁可在极端并发下退化成后写覆盖（下一帧协商会因为
     * 不在名单里而被 forbidden 拦下，用户看到的是「重试一次就好」），也不能让 WebSocket 的
     * 容器线程在这里无限期挂着——那会拖死同连接上的消息收发。
     *
     * @param mutator 返回 {@code null} 表示本次改动被业务拒绝（如人数已满），此时不写回 Redis；
     *                返回值为「需要通知的用户 ID 清单」，由调用方在锁外发送
     * @return mutator 的返回值，mutator 抛异常时不写回并向上传播
     */
    private List<Long> withState(String callId, java.util.function.Function<GroupCallState, List<Long>> mutator) {
        String lockKey = LOCK_PREFIX + callId;
        boolean locked = false;
        for (int i = 0; i < LOCK_RETRY && !locked; i++) {
            locked = redisUtil.setIfAbsent(lockKey, "1", LOCK_TTL);
            if (!locked) {
                try {
                    Thread.sleep(LOCK_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!locked) {
            log.warn("[群通话] 花名册锁未拿到，直接改（并发下可能被覆盖）: callId={}", callId);
        }
        try {
            GroupCallState fresh = load(callId);
            if (fresh == null) {
                return List.of();
            }
            List<Long> targets = mutator.apply(fresh);
            if (targets != null) {
                redisUtil.setObject(STATE_PREFIX + callId, fresh, stateTtl());
            }
            return targets == null ? List.of() : targets;
        } finally {
            if (locked) {
                redisUtil.delete(lockKey);
            }
        }
    }

    private GroupCallState load(String callId) {
        if (TextUtil.isBlank(callId)) {
            return null;
        }
        return redisUtil.getObject(STATE_PREFIX + callId, GroupCallState.class);
    }

    private WsGroupCallPayload.GroupCallMember member(GroupCallState state, Long userId) {
        for (WsGroupCallPayload.GroupCallMember m : state.getMembers()) {
            if (userId != null && userId.equals(m.getUserId())) {
                return m;
            }
        }
        return null;
    }

    private boolean isActiveMember(GroupCallState state, Long userId) {
        WsGroupCallPayload.GroupCallMember m = member(state, userId);
        return m != null && STATUS_ACTIVE.equals(m.getStatus());
    }

    private String deviceIdOf(GroupCallState state, Long userId) {
        WsGroupCallPayload.GroupCallMember m = member(state, userId);
        return m == null ? null : m.getDeviceId();
    }

    /** 花名册里的 ACTIVE 成员（排除自己），组帧时复制一份，不把内部对象直接交给序列化路径 */
    private List<WsGroupCallPayload.GroupCallMember> activeOthers(GroupCallState state, Long selfId) {
        List<WsGroupCallPayload.GroupCallMember> list = new ArrayList<>();
        for (WsGroupCallPayload.GroupCallMember m : state.getMembers()) {
            if (STATUS_ACTIVE.equals(m.getStatus()) && (selfId == null || !selfId.equals(m.getUserId()))) {
                list.add(copy(m));
            }
        }
        return list;
    }

    private WsGroupCallPayload.GroupCallMember copy(WsGroupCallPayload.GroupCallMember m) {
        WsGroupCallPayload.GroupCallMember c = new WsGroupCallPayload.GroupCallMember();
        c.setUserId(m.getUserId());
        c.setStatus(m.getStatus());
        c.setMuted(m.getMuted());
        c.setCameraOff(m.getCameraOff());
        c.setJoinedAt(m.getJoinedAt());
        return c;
    }

    private WsGroupCallPayload.GroupCallMember activeMember(Long userId, String deviceId, String mediaType) {
        WsGroupCallPayload.GroupCallMember m = new WsGroupCallPayload.GroupCallMember();
        m.setUserId(userId);
        m.setDeviceId(deviceId);
        m.setStatus(STATUS_ACTIVE);
        m.setMuted(false);
        // 语音通话没有摄像头可言，存 null 而不是 false，界面上就不该画一个「摄像头已关」的图标
        m.setCameraOff(MEDIA_VIDEO.equals(mediaType) ? Boolean.FALSE : null);
        m.setJoinedAt(System.currentTimeMillis());
        return m;
    }

    /* ==================== 组帧与投递 ==================== */

    private WsGroupCallPayload frame(String action, GroupCallState state, Long fromUserId) {
        WsGroupCallPayload out = new WsGroupCallPayload();
        out.setAction(action);
        out.setCallId(state.getCallId());
        out.setGroupId(state.getGroupId());
        out.setMediaType(state.getMediaType());
        out.setFromUserId(fromUserId);
        return out;
    }

    private WsPacket packet(WsGroupCallPayload payload) {
        return WsPacket.of(WsMessageType.GROUPCALL_SIGNAL, payload);
    }

    private void signalFailed(Long targetUser, String targetDevice, String callId, Long groupId, String reason) {
        WsGroupCallPayload out = new WsGroupCallPayload();
        out.setAction("failed");
        out.setCallId(callId);
        out.setGroupId(groupId);
        out.setReason(reason);
        if (TextUtil.isNotBlank(targetDevice)) {
            pushToDevice(targetUser, targetDevice, out);
        } else {
            pushSpi.pushToUser(targetUser, packet(out));
        }
    }

    private void pushToDevice(Long userId, String deviceId, WsGroupCallPayload payload) {
        if (TextUtil.isNotBlank(deviceId)) {
            pushSpi.pushToDevice(userId, deviceId, packet(payload));
        } else {
            pushSpi.pushToUser(userId, packet(payload));
        }
    }

    /**
     * 推给花名册里的某一位，按 TA 接听那台设备的 deviceId 精确投递，并天然跳过发起者自己。
     *
     * <p>为什么不能像普通消息那样 {@code pushToUser} 全端推：同一个人的电脑和手机可能同时在线，
     * 而只有接听的那台在通话里。把 member-join / member-leave 发给另一台设备，它会误以为自己
     * 也进了房，凭空对全体在线成员发起 offer。deviceId 缺失（条目已被改写、老客户端未上报）时
     * {@link #pushToDevice} 退化成本人全部设备，由前端按 callId 与本地状态比对兜底。
     */
    private void pushToMember(GroupCallState state, Long userId, WsGroupCallPayload payload) {
        if (userId == null || userId.equals(payload.getFromUserId())) {
            return;
        }
        pushToDevice(userId, deviceIdOf(state, userId), payload);
    }

    /**
     * 本场通话的人数上限（含发起人）。
     *
     * <p>公开给 {@code CallController} 下发给前端，是为了让两端的数字只有一个出处：
     * 前端自己再写一遍夹逼逻辑，就会出现「界面让人勾了 8 个、服务端默默截到 6 个」这种对不上账的提示。
     */
    public int maxSlots() {
        // 硬夹到 [2, 6]：低于 2 不成其为「多人」，高于 6 mesh 的上行必然崩（见 ImProperties.Call 的注释）
        int configured = imProperties.getCall().getGroupMaxSlots();
        if (configured < 2) {
            return 2;
        }
        return Math.min(configured, 6);
    }

    private Duration stateTtl() {
        return Duration.ofSeconds(imProperties.getCall().getMaxDurationSeconds() + 120L);
    }

    /**
     * 一场群通话的实时态，序列化成 JSON 存 Redis。
     *
     * <p>{@code members} 用 List 而不是 Map：JSON 里 Long 键会被写成字符串，读回来还得转，
     * 而成员数量小到可以线性查找（上限 6），顺序还顺便回答了「谁先进房」。
     */
    @Data
    public static class GroupCallState implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String callId;
        private Long groupId;
        /** 发起人：只有 TA 需要知道「谁拒了 / 谁无应答」，其余成员只看花名册 */
        private Long creatorUserId;
        private String mediaType;
        private long createdAt;
        private List<WsGroupCallPayload.GroupCallMember> members = new ArrayList<>();
    }
}
