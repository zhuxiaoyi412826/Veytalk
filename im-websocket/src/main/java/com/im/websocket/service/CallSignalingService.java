package com.im.websocket.service;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.WsPacket;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FriendRelationSpi;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 音视频通话信令编排：在两个已认证的用户连接之间转发 WebRTC 信令，并维护通话实时态。
 *
 * <h2>它只管信令，一帧媒体都不碰</h2>
 *
 * <p>语音 / 视频的实际内容走浏览器原生 WebRTC 点对点传输（内网时是 host 候选地址直连，根本不经过本服务器）；
 * 本服务搬运的是协商用的 SDP 与 ICE 候选——都是小 JSON，量级与一条聊天消息相当，复用现有 WebSocket 长连接即可，
 * 不需要额外的信令通道，也不需要 SFU。
 *
 * <h2>身份只认连接，不认报文</h2>
 *
 * <p>下行信令里的 {@code fromUserId} 一律由本服务按 {@link WsConnection#userId()} 覆写，
 * 客户端在 invite 里填的 fromUserId 直接丢弃。通话发起还强制过一遍好友 / 黑名单校验（{@link FriendRelationSpi}），
 * 与发消息同级，杜绝「给任意 userId 推来电」。
 *
 * <h2>多端振铃、首接胜出</h2>
 *
 * <p>invite 推给被叫的全部设备，任意一端 accept 后其余端收到 {@code canceled(answered-elsewhere)}；
 * 之后 offer/answer/ice 精确回到参与协商的那一对 deviceId，避免被叫的多台设备各自建一条 PeerConnection。
 *
 * <h2>实时态放 Redis 而非内存</h2>
 *
 * <p>callId → 状态 与 userId → 忙线指针都写 Redis 带 TTL：进程重启、振铃后主叫直接关掉页面（不发 cancel），
 * 都靠 TTL 与定时清扫收敛，不留悬空的忙线。当前推送仍是本进程内的（{@link PushSpi} 只认本地连接表），
 * 多实例部署需把转发跨节点化，与消息推送是同一道待办。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallSignalingService {

    /** 通话态主键前缀，定时清扫按它扫描（与忙线指针前缀区分开） */
    private static final String STATE_PREFIX = "im:call:state:";
    /** 忙线指针：userId → callId，invite 前先查它判忙 */
    private static final String USER_PREFIX = "im:call:user:";

    private static final String STATE_RINGING = "RINGING";
    private static final String STATE_ACTIVE = "ACTIVE";

    /** 媒体类型只认这两个值，其余归一化为 audio */
    private static final String MEDIA_VIDEO = "video";
    private static final String MEDIA_AUDIO = "audio";

    private final RedisUtil redisUtil;
    private final PushSpi pushSpi;
    private final JsonUtil jsonUtil;
    private final ImProperties imProperties;

    /**
     * 好友关系是可选协作方：好友模块缺席时通话仍然可用，但跳过好友校验（降级为「任意两端可互拨」）。
     * 用 {@link ObjectProvider} 与本模块其他地方对跨模块 SPI 的处理保持一致。
     */
    private final ObjectProvider<FriendRelationSpi> friendRelationProvider;

    /**
     * 处理一帧上行 {@code call} 信令。异常交由 {@code WsInboundDispatcher} 统一翻译成 error 帧、不断连，
     * 与 chat/delivered/read 的处置一致；因此这里只把「协议级非法」（缺 callId、报文解析失败）当异常抛出，
     * 业务级拒绝（忙线 / 不在线 / 非好友）都走 {@code call-signal} 主动回帧，不进异常通道。
     */
    public void handle(WsConnection connection, WsPacket packet) {
        ImProperties.Call call = imProperties.getCall();
        if (!call.isEnabled()) {
            // 功能关闭时不报错打扰，静默丢弃：前端本就应禁用入口，这里是兜底
            return;
        }
        WsCallPayload payload = jsonUtil.convert(packet.getData(), WsCallPayload.class);
        if (payload == null || TextUtil.isBlank(payload.getAction())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "call 报文载荷无法解析");
        }
        Long me = connection.userId();
        String deviceId = connection.deviceId();
        switch (payload.getAction()) {
            case "invite" -> handleInvite(me, deviceId, payload);
            case "accept" -> handleAccept(me, deviceId, payload);
            case "reject" -> handleTerminate(me, deviceId, payload, "reject");
            case "cancel" -> handleTerminate(me, deviceId, payload, "cancel");
            case "hangup" -> handleTerminate(me, deviceId, payload, "hangup");
            case "offer", "answer", "ice", "screen" -> handleRelay(me, deviceId, payload);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知的 call action: " + payload.getAction());
        }
    }

    /* ==================== invite ==================== */

    private void handleInvite(Long caller, String callerDeviceId, WsCallPayload payload) {
        Long callee = payload.getCalleeUserId();
        if (callee == null || callee.equals(caller)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "calleeUserId 非法");
        }
        // 好友 / 黑名单校验：与发消息同级，杜绝给陌生人或已拉黑者推来电
        FriendRelationSpi friendSpi = friendRelationProvider.getIfAvailable();
        if (friendSpi != null) {
            if (friendSpi.isBlockedEitherWay(caller, callee) || !friendSpi.isFriend(caller, callee)) {
                signalFailed(caller, callerDeviceId, payload.getCallId(), "not-friend");
                return;
            }
        }
        if (TextUtil.isBlank(payload.getCallId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "invite 缺少 callId");
        }
        // 主叫自己已在通话中：不允许一路没挂就拨第二路
        if (redisUtil.hasKey(USER_PREFIX + caller)) {
            signalFailed(caller, callerDeviceId, payload.getCallId(), "self-busy");
            return;
        }
        // 被叫已有活跃通话：直接忙线回主叫，省一次无谓振铃
        if (redisUtil.hasKey(USER_PREFIX + callee)) {
            signalFailed(caller, callerDeviceId, payload.getCallId(), "busy");
            return;
        }

        CallState state = new CallState();
        state.setCallId(payload.getCallId());
        state.setCallerUserId(caller);
        state.setCallerDeviceId(callerDeviceId);
        state.setCalleeUserId(callee);
        state.setMediaType(MEDIA_VIDEO.equals(payload.getMediaType()) ? MEDIA_VIDEO : MEDIA_AUDIO);
        state.setState(STATE_RINGING);
        state.setCreatedAt(System.currentTimeMillis());
        redisUtil.setObject(STATE_PREFIX + state.getCallId(), state, stateTtl());
        // 忙线指针：双方都占位，TTL 略长于状态，兜住「振铃后任一方掉线不发 cancel」
        Duration busyTtl = ringTtl().plus(Duration.ofSeconds(30));
        redisUtil.set(USER_PREFIX + caller, state.getCallId(), busyTtl);
        redisUtil.set(USER_PREFIX + callee, state.getCallId(), busyTtl);

        // 转发 invite 给被叫全部设备，fromUserId 用连接身份，客户端伪造的丢弃
        WsCallPayload out = base(payload.getCallId(), MEDIA_VIDEO.equals(state.getMediaType()) ? MEDIA_VIDEO : MEDIA_AUDIO, caller);
        if (!pushSpi.pushToUser(callee, packet(out))) {
            // 被叫当前进程无活跃连接：清掉刚建的状态与指针，回主叫「不在线」
            cleanup(state);
            signalFailed(caller, callerDeviceId, state.getCallId(), "offline");
        }
    }

    /* ==================== accept ==================== */

    private void handleAccept(Long callee, String calleeDeviceId, WsCallPayload payload) {
        CallState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(callee, calleeDeviceId, payload.getCallId(), "gone");
            return;
        }
        if (!callee.equals(state.getCalleeUserId())) {
            signalFailed(callee, calleeDeviceId, state.getCallId(), "forbidden");
            return;
        }
        if (!STATE_RINGING.equals(state.getState())) {
            // 已被别的设备接起或已结束：这台设备迟到的 accept 直接作废
            signalFailed(callee, calleeDeviceId, state.getCallId(), "gone");
            return;
        }
        state.setState(STATE_ACTIVE);
        state.setCalleeDeviceId(calleeDeviceId);
        state.setAcceptedAt(System.currentTimeMillis());
        redisUtil.setObject(STATE_PREFIX + state.getCallId(), state, activeTtl());

        // 通知主叫「对方已接听」，主叫侧据此发起 offer
        WsCallPayload toCaller = base(state.getCallId(), state.getMediaType(), callee);
        toCaller.setAction("accept");
        pushToDevice(state.getCallerUserId(), state.getCallerDeviceId(), toCaller);

        // 通知被叫其余设备「已在他端接听」，撤掉它们的来电界面（前端对自身这台会因已进入 active 而忽略）
        WsCallPayload toSiblings = base(state.getCallId(), state.getMediaType(), callee);
        toSiblings.setAction("canceled");
        toSiblings.setReason("answered-elsewhere");
        pushSpi.pushToUser(callee, packet(toSiblings));
    }

    /* ==================== reject / cancel / hangup ==================== */

    private void handleTerminate(Long me, String deviceId, WsCallPayload payload, String action) {
        CallState state = load(payload.getCallId());
        if (state == null) {
            return;
        }
        if (!isParticipant(me, state)) {
            signalFailed(me, deviceId, state.getCallId(), "forbidden");
            return;
        }
        // 转给对端；振铃期被叫可能还没定 deviceId，按用户全推
        WsCallPayload out = base(state.getCallId(), state.getMediaType(), me);
        out.setAction(action);
        out.setReason(payload.getReason());
        if (me.equals(state.getCallerUserId())) {
            pushToCallee(state, out);
        } else {
            pushToDevice(state.getCallerUserId(), state.getCallerDeviceId(), out);
        }
        cleanup(state);
    }

    /* ==================== offer / answer / ice 中继 ==================== */

    private void handleRelay(Long me, String deviceId, WsCallPayload payload) {
        CallState state = load(payload.getCallId());
        if (state == null) {
            signalFailed(me, deviceId, payload.getCallId(), "gone");
            return;
        }
        if (!isParticipant(me, state)) {
            signalFailed(me, deviceId, state.getCallId(), "forbidden");
            return;
        }
        WsCallPayload out = base(state.getCallId(), state.getMediaType(), me);
        out.setAction(payload.getAction());
        out.setSdpType(payload.getSdpType());
        out.setSdp(payload.getSdp());
        out.setCandidate(payload.getCandidate());
        // screen 动作只带共享布尔态，其余动作此字段为 null，中继时原样搬过去即可
        out.setSharing(payload.getSharing());
        if (me.equals(state.getCallerUserId())) {
            pushToCallee(state, out);
        } else {
            pushToDevice(state.getCallerUserId(), state.getCallerDeviceId(), out);
        }
        // 活跃通话中的每次协商都续一次 TTL，让 maxDuration 从「接通后」起算
        if (STATE_ACTIVE.equals(state.getState())) {
            redisUtil.expire(STATE_PREFIX + state.getCallId(), activeTtl());
        }
    }

    /* ==================== ICE 服务器清单 ==================== */

    /**
     * 组装 {@code RTCConfiguration.iceServers}：内网可只有 STUN 甚至都留空（host 直连够用），
     * 跨公网且配了 TURN 时追加一条带临时凭据的 TURN。
     *
     * @param userId 用于生成 TURN REST 凭据里的 username，与 coturn 的 {@code use-auth-secret} 约定一致
     */
    public List<Map<String, Object>> buildIceServers(Long userId) {
        ImProperties.Call call = imProperties.getCall();
        List<Map<String, Object>> servers = new ArrayList<>();
        List<String> stuns = call.getStunUrls();
        if (stuns != null && !stuns.isEmpty()) {
            Map<String, Object> stun = new LinkedHashMap<>();
            stun.put("urls", stuns);
            servers.add(stun);
        }
        ImProperties.Call.Turn turn = call.getTurn();
        if (turn != null && turn.isEnabled() && TextUtil.isNotBlank(turn.getSecret())
                && turn.getUrls() != null && !turn.getUrls().isEmpty()) {
            long expire = System.currentTimeMillis() / 1000 + Math.max(60, turn.getTtlSeconds());
            String username = expire + ":" + userId;
            String credential = hmacSha1Base64(turn.getSecret(), username);
            Map<String, Object> turnServer = new LinkedHashMap<>();
            turnServer.put("urls", turn.getUrls());
            turnServer.put("username", username);
            turnServer.put("credential", credential);
            servers.add(turnServer);
        }
        return servers;
    }

    private String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 密钥或算法异常属于配置错误，宁可不下发 TURN，也不能发一个错的凭据让客户端反复 407
            log.warn("TURN 凭据签名失败，跳过 TURN 下发: {}", e.getMessage());
            return "";
        }
    }

    /* ==================== 定时清扫：振铃超时与通话超长 ==================== */

    /**
     * 每 5 秒扫一遍进行中的通话，收掉两类悬空态：振铃超时（被叫没接）与超最长通话时长。
     *
     * <p>为什么必须有这一趟：超时是「没有任何信令进来」时才发生的事件——被叫不接、主叫直接关页面，
     * 帧驱动永远等不到触发点；TTL 能让 Redis 自己过期，但过期的同时要给在线另一端推一条「已超时」，
     * 否则对端界面会一直卡在振铃 / 通话中。
     */
    @Scheduled(fixedDelay = 5000)
    public void sweepExpired() {
        if (!imProperties.getCall().isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long ringMs = ringTtl().toMillis() - 30_000L; // ringTtl 含 30s slack，还原成纯振铃窗口
        long maxMs = activeTtl().toMillis();
        for (String key : redisUtil.keys(STATE_PREFIX + "*")) {
            CallState state = redisUtil.getObject(key, CallState.class);
            if (state == null) {
                continue;
            }
            if (STATE_RINGING.equals(state.getState())) {
                if (now - state.getCreatedAt() >= ringMs && now - state.getCreatedAt() < maxMs) {
                    notifyTimeout(state);
                }
            } else if (STATE_ACTIVE.equals(state.getState())) {
                long started = state.getAcceptedAt() > 0 ? state.getAcceptedAt() : state.getCreatedAt();
                if (now - started >= imProperties.getCall().getMaxDurationSeconds() * 1000L) {
                    notifyMaxDuration(state);
                }
            }
        }
    }

    private void notifyTimeout(CallState state) {
        // 振铃超时：通知主叫「无应答」，通知被叫其余设备「停止振铃」
        signalFailed(state.getCallerUserId(), state.getCallerDeviceId(), state.getCallId(), "timeout");
        WsCallPayload out = base(state.getCallId(), state.getMediaType(), state.getCalleeUserId());
        out.setAction("canceled");
        out.setReason("timeout");
        pushSpi.pushToUser(state.getCalleeUserId(), packet(out));
        redisUtil.delete(STATE_PREFIX + state.getCallId());
        clearBusy(state);
    }

    private void notifyMaxDuration(CallState state) {
        WsCallPayload out = base(state.getCallId(), state.getMediaType(), state.getCallerUserId());
        out.setAction("hangup");
        out.setReason("max-duration");
        pushToDevice(state.getCallerUserId(), state.getCallerDeviceId(), out);
        WsCallPayload toCallee = base(state.getCallId(), state.getMediaType(), state.getCalleeUserId());
        toCallee.setAction("hangup");
        toCallee.setReason("max-duration");
        pushToDevice(state.getCalleeUserId(), state.getCalleeDeviceId(), toCallee);
        redisUtil.delete(STATE_PREFIX + state.getCallId());
        clearBusy(state);
    }

    /* ==================== 内部工具 ==================== */

    /** 以服务端身份组一帧下行 call-signal；action 默认 invite，调用方按需改写 */
    private WsCallPayload base(String callId, String mediaType, Long fromUserId) {
        WsCallPayload out = new WsCallPayload();
        out.setAction("invite");
        out.setCallId(callId);
        out.setMediaType(mediaType);
        out.setFromUserId(fromUserId);
        return out;
    }

    /**
     * 向发起方回一帧失败 / 状态信令（busy / offline / timeout / gone / not-friend / forbidden / self-busy）。
     * 用专用 action=failed 携带 reason，前端据 reason 出文案并复位通话状态。
     */
    private void signalFailed(Long targetUser, String targetDevice, String callId, String reason) {
        WsCallPayload out = new WsCallPayload();
        out.setAction("failed");
        out.setCallId(callId);
        out.setReason(reason);
        if (TextUtil.isNotBlank(targetDevice)) {
            pushToDevice(targetUser, targetDevice, out);
        } else {
            pushSpi.pushToUser(targetUser, packet(out));
        }
    }

    private void pushToDevice(Long userId, String deviceId, WsCallPayload payload) {
        // deviceId 为空（振铃期对端设备尚未定）退化为发给该用户全部设备
        if (TextUtil.isNotBlank(deviceId)) {
            pushSpi.pushToDevice(userId, deviceId, packet(payload));
        } else {
            pushSpi.pushToUser(userId, packet(payload));
        }
    }

    private void pushToCallee(CallState state, WsCallPayload payload) {
        pushToDevice(state.getCalleeUserId(), state.getCalleeDeviceId(), payload);
    }

    private WsPacket packet(WsCallPayload payload) {
        return WsPacket.of(WsMessageType.CALL_SIGNAL, payload);
    }

    private boolean isParticipant(Long userId, CallState state) {
        return userId.equals(state.getCallerUserId()) || userId.equals(state.getCalleeUserId());
    }

    private CallState load(String callId) {
        if (TextUtil.isBlank(callId)) {
            return null;
        }
        return redisUtil.getObject(STATE_PREFIX + callId, CallState.class);
    }

    private void cleanup(CallState state) {
        redisUtil.delete(STATE_PREFIX + state.getCallId());
        clearBusy(state);
    }

    private void clearBusy(CallState state) {
        redisUtil.delete(USER_PREFIX + state.getCallerUserId());
        redisUtil.delete(USER_PREFIX + state.getCalleeUserId());
    }

    private Duration ringTtl() {
        return Duration.ofSeconds(imProperties.getCall().getRingTimeoutSeconds() + 30L);
    }

    private Duration activeTtl() {
        return Duration.ofSeconds(imProperties.getCall().getMaxDurationSeconds() + 60L);
    }

    private Duration stateTtl() {
        // 先按振铃窗口给 TTL；接通后 handleRelay/accept 会续到 activeTtl
        return Duration.ofSeconds(imProperties.getCall().getRingTimeoutSeconds()
                + imProperties.getCall().getMaxDurationSeconds() + 60L);
    }

    /**
     * 通话实时态，序列化成 JSON 存 Redis。字段用基本类型 + {@link String}，保证 Jackson 往返无损。
     */
    @Data
    public static class CallState implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String callId;
        private Long callerUserId;
        private String callerDeviceId;
        private Long calleeUserId;
        /** 接听那台被叫设备的 deviceId，accept 前为 null；offer/answer/ice 据此精确回投 */
        private String calleeDeviceId;
        private String mediaType;
        private String state;
        private long createdAt;
        private long acceptedAt;
    }
}
