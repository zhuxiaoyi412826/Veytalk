package com.im.websocket.service;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.WsPacket;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.LiveRoomSpi;
import com.im.common.spi.PushSpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import com.im.websocket.dto.WsCallPayload;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 直播连麦信令编排：在「某场直播的观众」与「该场主播」之间转发 WebRTC 信令，并维护在麦实时态。
 *
 * <h2>为什么不放在 im-live</h2>
 *
 * <p>连麦与音视频通话是同一件事的不同触发场景：都是 1:1 WebRTC、都要经已认证的 {@code /ws} 长连接搬运
 * SDP/ICE、都靠服务端下发 TURN 临时凭据。通话信令服务（{@link CallSignalingService}）已经在 im-websocket
 * 把这套骨架建好，连麦直接同处最省——可复用其 {@link CallSignalingService#buildIceServers} 与同一 {@link PushSpi}。
 * 而 im-websocket 与 im-live 是星型架构里两个互不依赖的模块，把本服务放 im-live 会让 dispatcher 调不到、
 * 也让 im-live 反向依赖 im-websocket。房间态与「本场观众」判定经 {@link LiveRoomSpi} 中转给 im-live 实现。
 *
 * <h2>与通话的三处不同</h2>
 * <ol>
 *   <li>会话主键含 roomId：一次连麦绑定一场直播，房间关播即应作废相关连麦（由 {@link LiveRoomSpi} 的 isLiving 兜底）。</li>
 *   <li>校验放宽：通话要求双方是好友，连麦只要求「发起者是本场活跃观众」，另一端恒定为该场主播（免填 callee）。</li>
 *   <li>容量：一个主播可同时接多名嘉宾（各一路 1:1），在麦人数上限由 {@code im.linkmic.max-slots} 约束。</li>
 * </ol>
 *
 * <h2>实时态放 Redis</h2>
 *
 * <p>与通话同源：{@code callId → 连麦态}、{@code guestUserId → 忙线指针}、{@code roomId → 在麦嘉宾集合} 都写 Redis 带 TTL，
 * 进程重启或掉线不发 leave 都靠 TTL 与定时清扫收敛。多实例部署下 pushToDevice 仍只认本进程连接，跨节点扇出是既有待办。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveLinkSignalingService {

    /** 连麦态主键：callId → LinkState */
    private static final String STATE_PREFIX = "im:link:state:";
    /** 嘉宾忙线指针：guestUserId → callId，一人同时只允许一路连麦 */
    private static final String USER_PREFIX = "im:link:user:";
    /** 在麦嘉宾集合：roomId → Set(guestUserId)，供 max-slots 判定与「当前在麦列表」查询 */
    private static final String MIC_PREFIX = "im:link:mic:";

    private static final String STATE_REQUESTING = "REQUESTING";
    private static final String STATE_ACTIVE = "ACTIVE";

    private final RedisUtil redisUtil;
    private final PushSpi pushSpi;
    private final JsonUtil jsonUtil;
    private final ImProperties imProperties;
    private final CallSignalingService callSignalingService;

    /** 房间态与观众判定经 SPI 由 im-live 提供；缺席时连麦不可用，直接拒（与通话的 FriendRelationSpi 同款可选协作方） */
    private final ObjectProvider<LiveRoomSpi> liveRoomSpiProvider;

    /**
     * 处理一帧上行 {@code linkmic} 信令。异常交由 {@code WsInboundDispatcher} 统一翻译成 error 帧、不断连；
     * 这里只把「协议级非法」（缺 roomId/callId、报文解析失败）当异常抛出，业务级拒绝走 {@code linkmic-signal} 主动回帧。
     */
    public void handle(WsConnection connection, WsPacket packet) {
        ImProperties.LinkMic cfg = imProperties.getLinkmic();
        if (!cfg.isEnabled()) {
            // 功能关闭时静默丢弃：前端本就应禁用入口，这里是兜底
            return;
        }
        WsCallPayload payload = jsonUtil.convert(packet.getData(), WsCallPayload.class);
        if (payload == null || TextUtil.isBlank(payload.getLinkAction())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "linkmic 报文载荷无法解析");
        }
        Long me = connection.userId();
        String deviceId = connection.deviceId();
        switch (payload.getLinkAction()) {
            case "request" -> handleRequest(me, deviceId, payload);
            case "accept" -> handleAccept(me, deviceId, payload);
            case "reject" -> handleTerminate(me, deviceId, payload, "reject");
            case "leave" -> handleTerminate(me, deviceId, payload, "leave");
            case "offer", "answer", "ice" -> handleRelay(me, deviceId, payload);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知的 linkAction: " + payload.getLinkAction());
        }
    }

    /* ==================== request（观众申请上麦） ==================== */

    private void handleRequest(Long guest, String guestDeviceId, WsCallPayload payload) {
        Long roomId = payload.getRoomId();
        if (roomId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "request 缺少 roomId");
        }
        if (TextUtil.isBlank(payload.getCallId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "request 缺少 callId");
        }
        LiveRoomSpi roomSpi = liveRoomSpiProvider.getIfAvailable();
        if (roomSpi == null) {
            throw new BusinessException(ResultCode.LIVE_LINK_DISABLED);
        }
        if (!roomSpi.isLiving(roomId)) {
            throw new BusinessException(ResultCode.LIVE_NOT_LIVING);
        }
        Long anchor = roomSpi.anchorIdOf(roomId);
        if (anchor == null) {
            throw new BusinessException(ResultCode.LIVE_ROOM_NOT_FOUND);
        }
        if (anchor.equals(guest)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "主播本人无需申请上麦");
        }
        // 放宽校验：只要求发起者是本场活跃观众（不要求好友）
        if (!roomSpi.isActiveViewer(roomId, guest)) {
            signalFailed(guest, guestDeviceId, payload.getCallId(), roomId, "not-viewer");
            return;
        }
        // 一人一路：该嘉宾已在某路连麦中
        if (redisUtil.hasKey(USER_PREFIX + guest)) {
            signalFailed(guest, guestDeviceId, payload.getCallId(), roomId, "self-busy");
            return;
        }
        // 麦位已满
        if (onMicCount(roomId) >= maxSlots()) {
            throw new BusinessException(ResultCode.LIVE_LINK_FULL);
        }

        LinkState state = new LinkState();
        state.setCallId(payload.getCallId());
        state.setRoomId(roomId);
        state.setGuestUserId(guest);
        state.setGuestDeviceId(guestDeviceId);
        state.setAnchorUserId(anchor);
        state.setState(STATE_REQUESTING);
        state.setCreatedAt(System.currentTimeMillis());
        redisUtil.setObject(STATE_PREFIX + state.getCallId(), state, stateTtl());
        redisUtil.set(USER_PREFIX + guest, state.getCallId(), requestTtl().plus(Duration.ofSeconds(30)));

        // 转发 request 给主播全部设备，fromUserId 用连接身份
        WsCallPayload out = base(state);
        out.setLinkAction("request");
        if (!pushSpi.pushToUser(anchor, packet(out))) {
            cleanup(state);
            signalFailed(guest, guestDeviceId, state.getCallId(), roomId, "anchor-offline");
        }
    }

    /* ==================== accept（主播同意上麦） ==================== */

    private void handleAccept(Long anchor, String anchorDeviceId, WsCallPayload payload) {
        LinkState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(anchor, anchorDeviceId, payload.getCallId(), payload.getRoomId(), "gone");
            return;
        }
        if (!anchor.equals(state.getAnchorUserId())) {
            signalFailed(anchor, anchorDeviceId, state.getCallId(), state.getRoomId(), "forbidden");
            return;
        }
        if (!STATE_REQUESTING.equals(state.getState())) {
            signalFailed(anchor, anchorDeviceId, state.getCallId(), state.getRoomId(), "gone");
            return;
        }
        // 并发接受时二次校验麦位（别的嘉宾可能刚被接受占满）
        if (onMicCount(state.getRoomId()) >= maxSlots()) {
            rejectToGuest(state, "full");
            cleanup(state);
            return;
        }
        state.setState(STATE_ACTIVE);
        state.setAnchorDeviceId(anchorDeviceId);
        state.setAcceptedAt(System.currentTimeMillis());
        redisUtil.setObject(STATE_PREFIX + state.getCallId(), state, activeTtl());
        // 入麦位集合，TTL 兜住关播/掉线未清理
        String micKey = MIC_PREFIX + state.getRoomId();
        redisUtil.sAdd(micKey, String.valueOf(state.getGuestUserId()));
        redisUtil.expire(micKey, activeTtl());

        // 有嘉宾正式上麦：通知房间内观众切到合成流（MSE/WebM）播放器
        publishLinkmic(state.getRoomId(), true);

        // 通知嘉宾「主播已同意」，嘉宾侧据此发起 offer
        WsCallPayload toGuest = base(state);
        toGuest.setLinkAction("accept");
        pushToDevice(state.getGuestUserId(), state.getGuestDeviceId(), toGuest);
    }

    /* ==================== reject / leave ==================== */

    private void handleTerminate(Long me, String deviceId, WsCallPayload payload, String action) {
        LinkState state = load(payload.getCallId());
        if (state == null) {
            return;
        }
        if (!isParticipant(me, state)) {
            signalFailed(me, deviceId, state.getCallId(), state.getRoomId(), "forbidden");
            return;
        }
        WsCallPayload out = base(state);
        out.setLinkAction(action);
        out.setReason(payload.getReason());
        pushToPeer(state, me, out);
        cleanup(state);
    }

    /* ==================== offer / answer / ice 中继 ==================== */

    private void handleRelay(Long me, String deviceId, WsCallPayload payload) {
        LinkState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(me, deviceId, payload.getCallId(), payload.getRoomId(), "gone");
            return;
        }
        if (!isParticipant(me, state)) {
            signalFailed(me, deviceId, state.getCallId(), state.getRoomId(), "forbidden");
            return;
        }
        WsCallPayload out = base(state);
        out.setLinkAction(payload.getLinkAction());
        out.setSdpType(payload.getSdpType());
        out.setSdp(payload.getSdp());
        out.setCandidate(payload.getCandidate());
        pushToPeer(state, me, out);
        // 活跃连麦每次协商续 TTL，让 maxDuration 从接通后起算
        if (STATE_ACTIVE.equals(state.getState())) {
            redisUtil.expire(STATE_PREFIX + state.getCallId(), activeTtl());
            redisUtil.expire(MIC_PREFIX + state.getRoomId(), activeTtl());
        }
    }

    /* ==================== ICE 配置（复用通话下发） ==================== */

    /** 连麦的 ICE 服务器清单：与通话同源，直接复用 {@link CallSignalingService#buildIceServers} */
    public List<java.util.Map<String, Object>> buildLinkIceServers(Long userId) {
        return callSignalingService.buildIceServers(userId);
    }

    /** 房间当前在麦嘉宾用户 ID 集合（供 REST「在麦列表」查询与前端渲染麦位） */
    public Set<Long> onMicUserIds(Long roomId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String member : redisUtil.sMembers(MIC_PREFIX + roomId)) {
            try {
                ids.add(Long.valueOf(member));
            } catch (NumberFormatException ignored) {
                // 脏数据直接跳过，不影响其余麦位
            }
        }
        return ids;
    }

    /* ==================== 定时清扫：申请超时与连麦超长 ==================== */

    @Scheduled(fixedDelay = 5000)
    public void sweepExpired() {
        if (!imProperties.getLinkmic().isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long requestMs = imProperties.getLinkmic().getRequestTimeoutSeconds() * 1000L;
        long maxMs = imProperties.getLinkmic().getMaxDurationSeconds() * 1000L;
        for (String key : redisUtil.keys(STATE_PREFIX + "*")) {
            LinkState state = redisUtil.getObject(key, LinkState.class);
            if (state == null) {
                continue;
            }
            if (STATE_REQUESTING.equals(state.getState())) {
                if (now - state.getCreatedAt() >= requestMs) {
                    signalFailed(state.getGuestUserId(), state.getGuestDeviceId(), state.getCallId(), state.getRoomId(), "timeout");
                    redisUtil.delete(key);
                    redisUtil.delete(USER_PREFIX + state.getGuestUserId());
                }
            } else if (STATE_ACTIVE.equals(state.getState())) {
                long started = state.getAcceptedAt() > 0 ? state.getAcceptedAt() : state.getCreatedAt();
                if (now - started >= maxMs) {
                    terminateBoth(state, "max-duration");
                }
            }
        }
    }

    private void terminateBoth(LinkState state, String reason) {
        WsCallPayload out = base(state);
        out.setLinkAction("leave");
        out.setReason(reason);
        pushToDevice(state.getGuestUserId(), state.getGuestDeviceId(), out);
        pushToDevice(state.getAnchorUserId(), state.getAnchorDeviceId(), out);
        cleanup(state);
    }

    /* ==================== 内部工具 ==================== */

    private WsCallPayload base(LinkState state) {
        WsCallPayload out = new WsCallPayload();
        out.setCallId(state.getCallId());
        out.setRoomId(state.getRoomId());
        out.setMediaType("video");
        // fromUserId 恒为「这一帧里作为发起方的那一位」，由调用方在组帧后按语义设置；默认填嘉宾，relay/terminate 会覆写
        return out;
    }

    private void signalFailed(Long targetUser, String targetDevice, String callId, Long roomId, String reason) {
        WsCallPayload out = new WsCallPayload();
        out.setLinkAction("failed");
        out.setCallId(callId);
        out.setRoomId(roomId);
        out.setReason(reason);
        if (TextUtil.isNotBlank(targetDevice)) {
            pushToDevice(targetUser, targetDevice, out);
        } else {
            pushSpi.pushToUser(targetUser, packet(out));
        }
    }

    private void rejectToGuest(LinkState state, String reason) {
        WsCallPayload out = base(state);
        out.setLinkAction("reject");
        out.setReason(reason);
        out.setFromUserId(state.getAnchorUserId());
        pushToDevice(state.getGuestUserId(), state.getGuestDeviceId(), out);
    }

    private void pushToDevice(Long userId, String deviceId, WsCallPayload payload) {
        if (TextUtil.isNotBlank(deviceId)) {
            pushSpi.pushToDevice(userId, deviceId, packet(payload));
        } else {
            pushSpi.pushToUser(userId, packet(payload));
        }
    }

    /** 把一帧转给「非 me 的另一方」：me 是嘉宾就发给主播（及其设备），是主播就发给嘉宾 */
    private void pushToPeer(LinkState state, Long me, WsCallPayload out) {
        out.setFromUserId(me);
        if (me.equals(state.getGuestUserId())) {
            pushToDevice(state.getAnchorUserId(), state.getAnchorDeviceId(), out);
        } else {
            pushToDevice(state.getGuestUserId(), state.getGuestDeviceId(), out);
        }
    }

    private WsPacket packet(WsCallPayload payload) {
        return WsPacket.of(WsMessageType.LINKMIC_SIGNAL, payload);
    }

    private boolean isParticipant(Long userId, LinkState state) {
        return userId.equals(state.getGuestUserId()) || userId.equals(state.getAnchorUserId());
    }

    private LinkState load(String callId) {
        if (TextUtil.isBlank(callId)) {
            return null;
        }
        return redisUtil.getObject(STATE_PREFIX + callId, LinkState.class);
    }

    private void cleanup(LinkState state) {
        boolean wasActive = STATE_ACTIVE.equals(state.getState());
        redisUtil.delete(STATE_PREFIX + state.getCallId());
        redisUtil.delete(USER_PREFIX + state.getGuestUserId());
        redisUtil.sRemove(MIC_PREFIX + state.getRoomId(), String.valueOf(state.getGuestUserId()));
        // 曾处于活跃连麦：若本房间已无其他嘉宾在麦，通知观众切回 HLS 播放器（还有人则保持合成流）
        if (wasActive && onMicCount(state.getRoomId()) == 0) {
            publishLinkmic(state.getRoomId(), false);
        }
    }

    /** 经 SPI 把连麦态变更扇出到 {@code /ws/live}；SPI 缺席（无直播模块）时静默，信令本身不受影响 */
    private void publishLinkmic(Long roomId, boolean active) {
        LiveRoomSpi roomSpi = liveRoomSpiProvider.getIfAvailable();
        if (roomSpi != null && roomId != null) {
            roomSpi.publishLinkmic(roomId, active);
        }
    }

    private int onMicCount(Long roomId) {
        return redisUtil.sMembers(MIC_PREFIX + roomId).size();
    }

    private int maxSlots() {
        return Math.max(1, imProperties.getLinkmic().getMaxSlots());
    }

    private Duration requestTtl() {
        return Duration.ofSeconds(imProperties.getLinkmic().getRequestTimeoutSeconds() + 30L);
    }

    private Duration activeTtl() {
        return Duration.ofSeconds(imProperties.getLinkmic().getMaxDurationSeconds() + 60L);
    }

    private Duration stateTtl() {
        return Duration.ofSeconds(imProperties.getLinkmic().getRequestTimeoutSeconds()
                + imProperties.getLinkmic().getMaxDurationSeconds() + 60L);
    }

    /**
     * 连麦实时态，序列化成 JSON 存 Redis。字段用基本类型 + {@link String}/{@link Long}，保证 Jackson 往返无损。
     */
    @Data
    public static class LinkState implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String callId;
        private Long roomId;
        private Long guestUserId;
        private String guestDeviceId;
        private Long anchorUserId;
        /** 主播接听那台设备的 deviceId，accept 前为 null；offer/answer/ice 据此精确回投 */
        private String anchorDeviceId;
        private String state;
        private long createdAt;
        private long acceptedAt;
    }
}
