package com.im.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.im.common.api.ResultCode;
import com.im.common.constant.RedisKeys;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.live.config.LiveProperties;
import com.im.live.dto.po.LivePkPO;
import com.im.live.dto.vo.LivePkVO;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连麦 PK：两个<b>都在直播</b>的房间，在一段时间窗内比热度。
 *
 * <h2>数据从哪来（没有一行新表）</h2>
 *
 * <ul>
 *   <li>礼物：{@code im:live:gift:heat:{roomId}}（送礼时按成交积分累加，见 {@code GiftServiceImpl}）；</li>
 *   <li>点赞：{@code im:live:like:heat:{roomId}}（由弹幕通道的 2 秒冲刷节拍顺手写入，见
 *       {@code LiveDanmakuService.flushLikes}）；</li>
 *   <li>投票：{@code im:pk:vote:{pkId}}，一人一个 Hash field，改票就是覆盖；</li>
 *   <li>会话本身：{@code im:pk:session:{pkId}}，另有一个 {@code im:pk:running} Set 供结算节拍遍历
 *       （不靠 {@code KEYS} 扫键）。</li>
 * </ul>
 *
 * <h2>为什么热度只算增量</h2>
 *
 * <p>两个房间的累计热度里含「PK 之前刷的」那部分，直接比累计值等于奖励先开播的人。
 * 所以开场那一刻采一次基线存进会话，之后每拍用 {@code 当前计数 - 基线} 算增量，
 * 负数一律按 0（Redis 键被清或计数回退不该让一方凭空掉热度，也不该让它变负）。
 *
 * <h2>为什么按 roomId 各算各的</h2>
 *
 * <p>嘉宾上麦后人在主播的房间里，但他自己那间房可以仍在直播——PK 比的是<b>两个房间各自的观众</b>，
 * 所以 A 方热度取自 A 房间、B 方取自 B 房间。也正因如此，对方必须在播（否则他那一路恒为 0，
 * 这场 PK 就没有意义），发起时直接拒。
 *
 * <h2>单实例约束</h2>
 *
 * <p>扇出经 {@link LiveDanmakuService#broadcast}，只认本进程的连接表；与弹幕、连麦是同一约束。
 * 会话与计数在 Redis，所以状态本身是全局的，只是「看不到帧的人」需要重进房才能拿到快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LivePkService {

    private static final String SIDE_A = "A";
    private static final String SIDE_B = "B";
    /** 帧的三种相位：开场 / 进行中（每拍）/ 结束，前端按它切换文案与是否继续跑倒计时 */
    private static final String PHASE_START = "start";
    private static final String PHASE_RUNNING = "running";
    private static final String PHASE_END = "end";

    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final LiveProperties properties;
    private final LiveRoomMapper roomMapper;
    private final UserQuerySpi userQuerySpi;
    private final LiveDanmakuService danmakuService;

    /* ==================== 发起 / 查询 ==================== */

    /**
     * 发起一场 PK。双方都必须正在直播——否则有一边永远 0 热度，比赛从一开始就是假的。
     */
    public LivePkVO start(Long anchorId, Long targetUserId, Integer durationSeconds) {
        LiveProperties.Pk cfg = requireEnabled();
        BusinessException.throwIf(targetUserId == null, ResultCode.BAD_REQUEST, "对手不能为空");
        BusinessException.throwIf(targetUserId.equals(anchorId), ResultCode.BAD_REQUEST, "不能和自己 PK");

        LiveRoom roomA = livingRoomOf(anchorId);
        BusinessException.throwIf(roomA == null, ResultCode.LIVE_NOT_LIVING);
        LiveRoom roomB = livingRoomOf(targetUserId);
        BusinessException.throwIf(roomB == null, ResultCode.LIVE_PK_OPPONENT_NOT_LIVING);

        // 一个房间同时只能有一场：否则两个 PK 的帧会互相覆盖，前端进度条来回跳
        BusinessException.throwIf(activePkOf(roomA.getId()) != null, ResultCode.LIVE_PK_RUNNING);
        BusinessException.throwIf(activePkOf(roomB.getId()) != null, ResultCode.LIVE_PK_RUNNING);

        int duration = durationSeconds == null || durationSeconds <= 0
                ? cfg.getDurationSeconds()
                : Math.min(durationSeconds, cfg.getMaxDurationSeconds());
        long now = System.currentTimeMillis();
        String pkId = IdWorker.getIdStr();
        LivePkPO po = LivePkPO.builder()
                .pkId(pkId)
                .status(LivePkPO.STATUS_RUNNING)
                .anchorA(anchorId)
                .roomA(roomA.getId())
                .nameA(nicknameOf(anchorId))
                .avatarA(avatarOf(anchorId))
                .anchorB(targetUserId)
                .roomB(roomB.getId())
                .nameB(nicknameOf(targetUserId))
                .avatarB(avatarOf(targetUserId))
                .startAt(now)
                .endAt(now + duration * 1000L)
                .durationSeconds(duration)
                // 基线在写会话之前采：这中间几秒内的礼物极可能被算进增量，
                // 反过来（先写会话再采）会让开场那一拍读到未定义的基线
                .baseGiftA(counter(RedisKeys.liveGiftHeat(roomA.getId())))
                .baseLikeA(counter(RedisKeys.liveLikeHeat(roomA.getId())))
                .baseGiftB(counter(RedisKeys.liveGiftHeat(roomB.getId())))
                .baseLikeB(counter(RedisKeys.liveLikeHeat(roomB.getId())))
                .build();

        saveSession(po, sessionTtl(po));
        touchRoomPointer(roomA.getId(), pkId, sessionTtl(po));
        touchRoomPointer(roomB.getId(), pkId, sessionTtl(po));
        redisUtil.sAdd(RedisKeys.pkRunning(), pkId);

        LivePkVO vo = snapshot(po, now);
        broadcast(po, vo, PHASE_START);
        log.info("PK 开始: pkId={}, roomA={}, roomB={}, duration={}s", pkId, roomA.getId(), roomB.getId(), duration);
        return vo;
    }

    /** 房间当前这场 PK（进行中或刚结束的快照），没有则 null。给 REST 与进房补帧共用 */
    public LivePkVO current(Long roomId) {
        LivePkPO po = activePkOf(roomId);
        if (po == null) {
            String pkId = redisUtil.get(RedisKeys.pkRoom(roomId));
            if (pkId == null || pkId.isBlank()) {
                return null;
            }
            // 指针还在但会话已结束：把定格结果还给前端，让它能显示「谁赢了」而不是进度条凭空消失
            po = loadSession(pkId);
            if (po == null) {
                redisUtil.delete(RedisKeys.pkRoom(roomId));
                return null;
            }
        }
        return snapshot(po, System.currentTimeMillis());
    }

    /** 进房时如果正有 PK，补一帧当前态（否则晚来的观众只看到一条中缝，不知道它在比什么） */
    public void sendCurrent(WebSocketSession session, Long roomId) {
        LivePkVO vo = current(roomId);
        if (vo == null) {
            return;
        }
        LivePkPO po = loadSession(vo.getPkId());
        if (po == null) {
            return;
        }
        String phase = vo.getStatus() != null && vo.getStatus() == LivePkPO.STATUS_RUNNING ? PHASE_RUNNING : PHASE_END;
        danmakuService.sendTo(session, frame(phase, po, vo));
    }

    /* ==================== 投票 ==================== */

    /**
     * 观众投票站队。不做「必须是本房间观众」的校验：PK 的票数只按人头计，
     * 跨房间串票的收益远低于为此付出的每次投票都要查一遍连接表的成本。
     *
     * @return 投票后的双方票数快照
     */
    public LivePkVO vote(Long userId, String pkId, String side) {
        requireEnabled();
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
        String chosen = side == null ? "" : side.trim().toUpperCase();
        BusinessException.throwUnless(SIDE_A.equals(chosen) || SIDE_B.equals(chosen),
                ResultCode.BAD_REQUEST, "只能投 A 或 B");
        LivePkPO po = loadSession(pkId);
        BusinessException.throwIf(po == null, ResultCode.LIVE_PK_NOT_FOUND);
        BusinessException.throwIf(po.getStatus() != LivePkPO.STATUS_RUNNING, ResultCode.LIVE_PK_NOT_FOUND);

        String key = RedisKeys.pkVote(pkId);
        redisUtil.hSet(key, String.valueOf(userId), chosen);
        // 票是低频写，但键的寿命要跟会话一致，否则会话还在、票数先被回收了
        redisUtil.expire(key, sessionTtl(po));
        return snapshot(po, System.currentTimeMillis());
    }

    /* ==================== 结束 ==================== */

    /** 主播提前结束（{@code manual}）；只有对阵双方的主播能这么喊停 */
    public LivePkVO stop(Long anchorId, String pkId) {
        LivePkPO po = loadSession(pkId);
        BusinessException.throwIf(po == null, ResultCode.LIVE_PK_NOT_FOUND);
        boolean party = po.getAnchorA().equals(anchorId) || po.getAnchorB().equals(anchorId);
        BusinessException.throwUnless(party, ResultCode.LIVE_FORBIDDEN);
        return settle(po, "manual");
    }

    /** 结束一场 PK 并扇出结果；已结束的会话重复调用直接返回定格值（幂等） */
    private LivePkVO settle(LivePkPO po, String reason) {
        long now = System.currentTimeMillis();
        LivePkVO vo = snapshot(po, now);
        if (po.getStatus() != LivePkPO.STATUS_RUNNING) {
            return vo;
        }
        po.setStatus("time".equals(reason) ? LivePkPO.STATUS_FINISHED : LivePkPO.STATUS_CANCELED);
        po.setEndReason(reason);
        // 定格一份，事后（回看、主播数据面板）就不必再依赖那些会过期的计数器
        po.setHeatA(vo.getHeatA());
        po.setHeatB(vo.getHeatB());
        po.setVoteA(vo.getVoteA());
        po.setVoteB(vo.getVoteB());
        long heatA = vo.getHeatA();
        long heatB = vo.getHeatB();
        po.setWinner(heatA == heatB ? LivePkPO.WINNER_DRAW : (heatA > heatB ? SIDE_A : SIDE_B));

        redisUtil.set(RedisKeys.pkSession(po.getPkId()), jsonUtil.toJson(po),
                Duration.ofMinutes(Math.max(1, properties.getPk().getSessionRetentionMinutes())));
        redisUtil.delete(RedisKeys.pkRoom(po.getRoomA()));
        redisUtil.delete(RedisKeys.pkRoom(po.getRoomB()));
        redisUtil.sRemove(RedisKeys.pkRunning(), po.getPkId());

        broadcast(po, snapshot(po, now), PHASE_END);
        log.info("PK 结束: pkId={}, winner={}, reason={}, heatA={}, heatB={}",
                po.getPkId(), po.getWinner(), reason, po.getHeatA(), po.getHeatB());
        return snapshot(po, now);
    }

    /* ==================== 节拍 ==================== */

    /**
     * 每秒一拍：广播进度帧、到点结算、顺手回收已经结束的会话。
     *
     * <p>1 秒而不是跟弹幕巡检共用 2 秒：倒计时是以秒为单位跳的，节拍比显示粗一档
     * 就会看到「剩余时间卡在 3 秒不动然后突然归零」。
     *
     * @return 本拍广播过的会话数（供日志）
     */
    public int tick() {
        Set<String> pkIds = redisUtil.sMembers(RedisKeys.pkRunning());
        if (pkIds == null || pkIds.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int sent = 0;
        List<String> dead = new ArrayList<>(4);
        for (String pkId : pkIds) {
            LivePkPO po = loadSession(pkId);
            if (po == null) {
                dead.add(pkId);
                continue;
            }
            if (po.getStatus() != LivePkPO.STATUS_RUNNING) {
                dead.add(pkId);
                continue;
            }
            // 任一方关播或被封，这场就没有比下去的意义了：按当时热度结算，不静默丢弃
            if (!stillLiving(po.getRoomA()) || !stillLiving(po.getRoomB())) {
                settle(po, "room-ended");
                continue;
            }
            if (now >= po.getEndAt()) {
                settle(po, "time");
                continue;
            }
            broadcast(po, snapshot(po, now), PHASE_RUNNING);
            sent++;
        }
        for (String pkId : dead) {
            // 会话键自己会过期，这里只是把索引清干净，免得每次节拍都白读一遍不存在的键
            redisUtil.sRemove(RedisKeys.pkRunning(), pkId);
        }
        return sent;
    }

    /* ==================== 热度与快照 ==================== */

    /**
     * 组装一帧对外快照：实时读计数算增量，再叠上票数。
     *
     * <p>已结算的会话不再重算——那几个热度键可能在 TTL 之后被清，届时增量算成 0 会把
     * 定格好的胜负结果改写成平局。
     */
    private LivePkVO snapshot(LivePkPO po, long now) {
        long heatA;
        long heatB;
        int voteA;
        int voteB;
        if (po.getStatus() == LivePkPO.STATUS_RUNNING) {
            int[] votes = voteCounts(po.getPkId());
            voteA = votes[0];
            voteB = votes[1];
            LiveProperties.Pk cfg = properties.getPk();
            heatA = heat(counter(RedisKeys.liveGiftHeat(po.getRoomA())) - nz(po.getBaseGiftA()),
                    counter(RedisKeys.liveLikeHeat(po.getRoomA())) - nz(po.getBaseLikeA()),
                    voteA, cfg);
            heatB = heat(counter(RedisKeys.liveGiftHeat(po.getRoomB())) - nz(po.getBaseGiftB()),
                    counter(RedisKeys.liveLikeHeat(po.getRoomB())) - nz(po.getBaseLikeB()),
                    voteB, cfg);
        } else {
            heatA = nz(po.getHeatA());
            heatB = nz(po.getHeatB());
            voteA = po.getVoteA() == null ? 0 : po.getVoteA();
            voteB = po.getVoteB() == null ? 0 : po.getVoteB();
        }
        long remain = Math.max(0L, (po.getEndAt() - now + 999) / 1000);
        return LivePkVO.builder()
                .pkId(po.getPkId())
                .status(po.getStatus())
                .anchorA(po.getAnchorA())
                .roomA(po.getRoomA())
                .nameA(po.getNameA())
                .avatarA(po.getAvatarA())
                .anchorB(po.getAnchorB())
                .roomB(po.getRoomB())
                .nameB(po.getNameB())
                .avatarB(po.getAvatarB())
                .durationSeconds(po.getDurationSeconds())
                .remainSeconds(po.getStatus() == LivePkPO.STATUS_RUNNING ? remain : 0L)
                .heatA(heatA)
                .heatB(heatB)
                .voteA(voteA)
                .voteB(voteB)
                .ratioA(ratio(heatA, heatB))
                .winner(po.getWinner())
                .endReason(po.getEndReason())
                .build();
    }

    /** 增量 × 权重，负数截零：计数键被清或 Redis 重启过，不该让某一方挂着负热度继续比 */
    private long heat(long giftDelta, long likeDelta, int votes, LiveProperties.Pk cfg) {
        long gift = Math.max(0L, giftDelta) * Math.max(0, cfg.getGiftWeight());
        long like = Math.max(0L, likeDelta) * Math.max(0, cfg.getLikeWeight());
        long vote = (long) Math.max(0, votes) * Math.max(0, cfg.getVoteWeight());
        return gift + like + vote;
    }

    /** 百分比而非 0~1 小数：前端直接当宽度用，不必再处理「双方都是 0」除出 NaN */
    private int ratio(long a, long b) {
        long total = a + b;
        if (total <= 0) {
            return 50;
        }
        return (int) Math.round(a * 100.0 / total);
    }

    /** 一次 HGETALL 数两边的人头：百人房间最多几百个 field，比每拍两次 HLEN 扫描更省事 */
    private int[] voteCounts(String pkId) {
        Map<Object, Object> all = redisUtil.hGetAll(RedisKeys.pkVote(pkId));
        int a = 0;
        int b = 0;
        for (Object value : all.values()) {
            if (SIDE_B.equalsIgnoreCase(String.valueOf(value))) {
                b++;
            } else {
                a++;
            }
        }
        return new int[]{a, b};
    }

    /* ==================== 帧 ==================== */

    private void broadcast(LivePkPO po, LivePkVO vo, String phase) {
        Map<String, Object> frame = frame(phase, po, vo);
        danmakuService.broadcast(po.getRoomA(), frame);
        danmakuService.broadcast(po.getRoomB(), frame);
    }

    /**
     * 摊平成弹幕帧。带 roomA/roomB 是因为两个房间收到的<b>是同一帧</b>：
     * 前端按自己所在的 roomId 判断自己站哪一边，否则左右两边会镜像颠倒。
     */
    private Map<String, Object> frame(String phase, LivePkPO po, LivePkVO vo) {
        Map<String, Object> frame = danmakuService.frame(LiveDanmakuService.TYPE_PK, "phase", phase);
        frame.put("pkId", vo.getPkId());
        frame.put("status", vo.getStatus());
        frame.put("remainSeconds", vo.getRemainSeconds());
        frame.put("durationSeconds", vo.getDurationSeconds());
        frame.put("heatA", vo.getHeatA());
        frame.put("heatB", vo.getHeatB());
        frame.put("voteA", vo.getVoteA());
        frame.put("voteB", vo.getVoteB());
        frame.put("ratioA", vo.getRatioA());
        frame.put("roomA", po.getRoomA());
        frame.put("roomB", po.getRoomB());
        frame.put("anchorA", po.getAnchorA());
        frame.put("anchorB", po.getAnchorB());
        frame.put("nameA", vo.getNameA());
        frame.put("nameB", vo.getNameB());
        frame.put("avatarA", vo.getAvatarA());
        frame.put("avatarB", vo.getAvatarB());
        frame.put("winner", vo.getWinner());
        frame.put("endReason", vo.getEndReason());
        frame.put("ts", System.currentTimeMillis());
        return frame;
    }

    /* ==================== Redis 读写细节 ==================== */

    private LiveProperties.Pk requireEnabled() {
        LiveProperties.Pk cfg = properties.getPk();
        BusinessException.throwIf(cfg == null || !cfg.isEnabled(), ResultCode.LIVE_PK_DISABLED);
        return cfg;
    }

    private LivePkPO loadSession(String pkId) {
        if (pkId == null || pkId.isBlank()) {
            return null;
        }
        return jsonUtil.fromJsonQuietly(redisUtil.get(RedisKeys.pkSession(pkId)), LivePkPO.class);
    }

    /** 会话键的寿命 = 剩余对局时间 + 保留期：结束时会被改写成只按保留期计 */
    private Duration sessionTtl(LivePkPO po) {
        long remainMillis = Math.max(0L, po.getEndAt() - System.currentTimeMillis());
        return Duration.ofMillis(remainMillis)
                .plus(Duration.ofMinutes(Math.max(1, properties.getPk().getSessionRetentionMinutes())));
    }

    private void saveSession(LivePkPO po, Duration ttl) {
        redisUtil.set(RedisKeys.pkSession(po.getPkId()), jsonUtil.toJson(po), ttl);
    }

    private void touchRoomPointer(Long roomId, String pkId, Duration ttl) {
        redisUtil.set(RedisKeys.pkRoom(roomId), pkId, ttl);
    }

    /** 只认进行中的会话：已结束的走 {@link #current} 的定格分支，不参与「是否已在 PK 中」的判定 */
    private LivePkPO activePkOf(Long roomId) {
        String pkId = redisUtil.get(RedisKeys.pkRoom(roomId));
        LivePkPO po = loadSession(pkId);
        if (po == null) {
            return null;
        }
        return po.getStatus() == LivePkPO.STATUS_RUNNING ? po : null;
    }

    /** Redis 计数器读取：脏值与缺失都按 0，绝不让一场正在进行的 PK 因为一条坏数据报错中断 */
    private long counter(String key) {
        String value = redisUtil.get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private boolean stillLiving(Long roomId) {
        LiveRoom room = roomMapper.selectById(roomId);
        return room != null && room.isLiving();
    }

    /** 某主播正在直播的那间房（一账号只允许一场，limit 1 足够） */
    private LiveRoom livingRoomOf(Long anchorId) {
        return roomMapper.selectOne(new LambdaQueryWrapper<LiveRoom>()
                .eq(LiveRoom::getAnchorId, anchorId)
                .eq(LiveRoom::getStatus, LiveRoom.STATUS_LIVING)
                .orderByDesc(LiveRoom::getStartTime)
                .last("limit 1"));
    }

    private String nicknameOf(Long userId) {
        var user = userQuerySpi.getById(userId);
        return user == null ? "主播" : user.getNickname();
    }

    private String avatarOf(Long userId) {
        var user = userQuerySpi.getById(userId);
        return user == null ? null : user.getAvatar();
    }
}
