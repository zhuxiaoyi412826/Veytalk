package com.im.live.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.RedisUtil;
import com.im.common.util.SecurityUtil;
import com.im.live.config.LiveProperties;
import com.im.live.dto.req.LiveStartRequest;
import com.im.live.dto.vo.LiveRoomVO;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import com.im.live.service.LiveDanmakuService;
import com.im.live.service.LiveRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 直播房间服务实现（控制面）。
 *
 * <h2>streamKey 的双重身份</h2>
 *
 * <p>它既是这场直播在 Nginx 上的目录名，也是不可猜的能力凭证：
 * {@code /{roomId}/{streamKey}/index.m3u8}。roomId 是雪花 ID、可枚举性低但并非秘密，
 * 真正的门禁是 streamKey——每场随机生成、关播即置空。即便有人拿到过期的签名地址，
 * 没有本场 key 也定位不到分片目录。
 *
 * <h2>播放地址签名</h2>
 *
 * <p>算法固定 {@code md5(path + expire + secret)}，与 Nginx
 * {@code secure_link_md5 "$uri$arg_expire$secret"} 逐字对应；path 必须是 Nginx 看到的
 * {@code $uri}（含 location 前缀），所以这里从 {@code playBaseUrl} 里把 path 抠出来拼接，
 * 而不是拿整个 URL 去算——多算进 scheme/host 会让签名永远对不上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveRoomServiceImpl implements LiveRoomService {

    private final LiveRoomMapper roomMapper;
    private final LiveProperties properties;
    private final RedisUtil redisUtil;
    private final UserQuerySpi userQuerySpi;
    private final LiveDanmakuService danmakuService;

    /** streamKey 用密码学随机源：它是能力凭证，可预测等于形同虚设 */
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] KEY_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int STREAM_KEY_LENGTH = 24;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(LiveStartRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.LIVE_DISABLED);
        }
        // 两个基址缺一个都开不了播：下发一个拼不出来的相对地址，要等到播放器 404 才暴露，
        // 排查成本远高于开播这一刻直接报错。
        if (isBlank(properties.getPlayBaseUrl()) || isBlank(properties.getPushBaseUrl())) {
            throw new BusinessException(ResultCode.LIVE_NOT_CONFIGURED);
        }
        Long userId = SecurityUtil.getUserId();

        Long living = roomMapper.selectCount(new LambdaQueryWrapper<LiveRoom>()
                .eq(LiveRoom::getAnchorId, userId)
                .eq(LiveRoom::getStatus, LiveRoom.STATUS_LIVING));
        if (living != null && living >= properties.getMaxLivingRoomsPerUser()) {
            throw new BusinessException(ResultCode.LIVE_ALREADY_LIVING);
        }

        LiveRoom room = new LiveRoom();
        room.setAnchorId(userId);
        room.setTitle(request.getTitle());
        room.setCover(request.getCover());
        room.setNotice(request.getNotice());
        room.setSourceType(isBlank(request.getSourceType()) ? LiveRoom.SOURCE_SCREEN : request.getSourceType());
        room.setResolution(request.getResolution());
        room.setBitrateKbps(request.getBitrateKbps());
        room.setStreamKey(randomStreamKey());
        room.setStatus(LiveRoom.STATUS_LIVING);
        room.setPeakOnline(0);
        room.setViewerTotal(0);
        room.setStartTime(LocalDateTime.now());
        roomMapper.insert(room);

        // 播种心跳键：TTL = 心跳间隔 × 超时倍数。推流端必须在 TTL 内续期，
        // 否则巡检任务会判定断播并自动关播。
        seedHeartbeat(room.getId());

        String dir = room.getId() + "/" + room.getStreamKey();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", room.getId());
        result.put("streamKey", room.getStreamKey());
        // 推流目标是 m3u8 播放列表地址，ffmpeg 以 -method PUT 上传列表与分片
        result.put("pushUrl", joinUrl(properties.getPushBaseUrl(), dir + "/index.m3u8"));
        result.put("playUrl", signedPlayUrl(room.getId(), room.getStreamKey()));
        result.put("heartbeatSeconds", properties.getHeartbeatSeconds());
        result.put("danmakuWs", danmakuWsPath(room.getId()));
        log.info("开播: roomId={}, anchorId={}, source={}", room.getId(), userId, room.getSourceType());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stop(Long roomId, String reason) {
        LiveRoom room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new BusinessException(ResultCode.LIVE_ROOM_NOT_FOUND);
        }
        if (!room.isLiving()) {
            // 幂等：推流端超时关播与主播主动关播可能撞在一起，第二次到达时房间已结束，
            // 直接返回而不是报错，否则前端会弹一个「直播已结束」的误导提示。
            return;
        }
        Long userId = SecurityUtil.getUserIdOrNull();
        boolean ban = "ban".equals(reason);
        if (!ban) {
            // 主动/超时关播要校验归属；封禁由管理端触发，走 ban 分支跳过归属校验
            if (userId == null || !room.managedBy(userId)) {
                throw new BusinessException(ResultCode.LIVE_FORBIDDEN);
            }
        }
        closeRoom(room, ban ? LiveRoom.STATUS_BANNED : LiveRoom.STATUS_ENDED, reason);
    }

    @Override
    public boolean heartbeat(Long roomId) {
        LiveRoom room = roomMapper.selectById(roomId);
        if (room == null || !room.isLiving()) {
            return false;
        }
        Long userId = SecurityUtil.getUserIdOrNull();
        if (userId == null || !room.managedBy(userId)) {
            // 心跳必须来自主播本人：否则任何人拿到 roomId 都能续着一个早该关播的房间
            throw new BusinessException(ResultCode.LIVE_FORBIDDEN);
        }
        seedHeartbeat(roomId);
        return true;
    }

    @Override
    public LiveRoomVO detail(Long roomId) {
        LiveRoom room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new BusinessException(ResultCode.LIVE_ROOM_NOT_FOUND);
        }
        Map<Long, UserBriefDTO> anchors = userQuerySpi.listByIds(List.of(room.getAnchorId()));
        LiveRoomVO vo = toVO(room, anchors.get(room.getAnchorId()));
        vo.setMine(room.managedBy(SecurityUtil.getUserIdOrNull()));
        return vo;
    }

    @Override
    public Page<LiveRoomVO> page(long current, long size, Integer status) {
        Page<LiveRoom> page = new Page<>(current, size);
        LambdaQueryWrapper<LiveRoom> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(LiveRoom::getStatus, status);
        }
        // 已结束且关播超过 endedRoomVisibleMinutes 的房间不再展示：观众不该翻到一堆早就散场的历史场次。
        // 条件写成「非已结束 OR 无 endTime OR endTime 在阈值内」——直播中(1)、已封禁(3)天然满足第一条不受影响，
        // endTime 为空的脏数据也不会被误藏；与上面的 status 过滤是 AND，选「已结束」时只剩阈值内刚结束的场次。
        // 配成 0 或负数则关闭该过滤（历史场次全部保留），方便需要回看全部已结束房间的运营场景。
        int endedVisibleMinutes = properties.getEndedRoomVisibleMinutes();
        if (endedVisibleMinutes > 0) {
            LocalDateTime endedCutoff = LocalDateTime.now().minusMinutes(endedVisibleMinutes);
            wrapper.and(w -> w.ne(LiveRoom::getStatus, LiveRoom.STATUS_ENDED)
                    .or().isNull(LiveRoom::getEndTime)
                    .or().ge(LiveRoom::getEndTime, endedCutoff));
        }
        // 直播中恒排最前，其次按开播时间倒序：列表页要的是「现在能看的」在最上面
        wrapper.orderByAsc(LiveRoom::getStatus)
                .orderByDesc(LiveRoom::getStartTime);
        Page<LiveRoom> result = roomMapper.selectPage(page, wrapper);

        Page<LiveRoomVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<LiveRoom> records = result.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Set<Long> anchorIds = new HashSet<>();
        for (LiveRoom room : records) {
            anchorIds.add(room.getAnchorId());
        }
        Map<Long, UserBriefDTO> anchors = userQuerySpi.listByIds(anchorIds);
        Long me = SecurityUtil.getUserIdOrNull();
        List<LiveRoomVO> vos = new ArrayList<>(records.size());
        for (LiveRoom room : records) {
            LiveRoomVO vo = toVO(room, anchors.get(room.getAnchorId()));
            vo.setMine(room.managedBy(me));
            vos.add(vo);
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public LiveRoomVO mine() {
        Long userId = SecurityUtil.getUserId();
        LambdaQueryWrapper<LiveRoom> wrapper = new LambdaQueryWrapper<LiveRoom>()
                .eq(LiveRoom::getAnchorId, userId)
                // 直播中优先，其余按开播时间倒序：主播回到面板要能立刻接上正在播的那场
                .orderByAsc(LiveRoom::getStatus)
                .orderByDesc(LiveRoom::getStartTime)
                .last("limit 1");
        LiveRoom room = roomMapper.selectOne(wrapper);
        if (room == null) {
            return null;
        }
        LiveRoomVO vo = toVO(room, userQuerySpi.getById(userId));
        vo.setMine(true);
        return vo;
    }

    @Override
    public void expireTimeouts() {
        // 点赞合并计数每拍冲刷一次：与超时巡检共用节拍，省一个独立的调度项
        danmakuService.flushLikes();

        List<LiveRoom> living = roomMapper.selectList(new LambdaQueryWrapper<LiveRoom>()
                .eq(LiveRoom::getStatus, LiveRoom.STATUS_LIVING));
        for (LiveRoom room : living) {
            if (redisUtil.hasKey(RedisKeys.liveHeartbeat(room.getId()))) {
                continue;
            }
            log.info("直播心跳超时，自动关播: roomId={}, anchorId={}", room.getId(), room.getAnchorId());
            closeRoom(room, LiveRoom.STATUS_ENDED, "timeout");
        }
    }

    @Override
    public LiveRoom findLiving(Long roomId) {
        if (roomId == null) {
            return null;
        }
        LiveRoom room = roomMapper.selectById(roomId);
        return room != null && room.isLiving() ? room : null;
    }

    /* ==================== 内部 ==================== */

    /**
     * 关播收尾：定格统计、作废 streamKey、断开房间内全部连接、清心跳键。
     *
     * <p>streamKey 置空是有意的：它是本场地址的能力凭证，关播后即便分片目录还在
     * Nginx 上（供回放），旧的签名地址也无法再定位——回放走另一套不带 key 的持久地址。
     */
    private void closeRoom(LiveRoom room, int status, String reason) {
        int[] stats = danmakuService.drainStats(room.getId());
        room.setStatus(status);
        room.setEndReason(reason);
        room.setEndTime(LocalDateTime.now());
        room.setPeakOnline(stats[0]);
        room.setViewerTotal(stats[1]);
        room.setStreamKey(null);
        roomMapper.updateById(room);

        danmakuService.closeRoom(room.getId(), reasonText(reason));
        redisUtil.delete(RedisKeys.liveHeartbeat(room.getId()));
        redisUtil.delete(RedisKeys.liveOnline(room.getId()));
        log.info("关播: roomId={}, reason={}, peakOnline={}, viewerTotal={}",
                room.getId(), reason, stats[0], stats[1]);
    }

    private String reasonText(String reason) {
        if ("timeout".equals(reason)) {
            return "主播已断开，直播结束";
        }
        if ("ban".equals(reason)) {
            return "直播已被管理员中断";
        }
        return "主播已结束直播";
    }

    private void seedHeartbeat(Long roomId) {
        long ttl = (long) properties.getHeartbeatSeconds() * properties.getHeartbeatTimeoutFactor();
        // 值本身用不上，键的存在性即「还活着」；TTL 到期自动消失就是超时信号
        redisUtil.set(RedisKeys.liveHeartbeat(roomId), String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(ttl));
    }

    private LiveRoomVO toVO(LiveRoom room, UserBriefDTO anchor) {
        LiveRoomVO vo = new LiveRoomVO();
        vo.setId(room.getId());
        vo.setAnchorId(room.getAnchorId());
        if (anchor != null) {
            vo.setAnchorName(anchor.getNickname());
            vo.setAnchorAvatar(anchor.getAvatar());
        }
        vo.setTitle(room.getTitle());
        vo.setCover(room.getCover());
        vo.setNotice(room.getNotice());
        vo.setSourceType(room.getSourceType());
        vo.setResolution(room.getResolution());
        vo.setBitrateKbps(room.getBitrateKbps());
        vo.setStatus(room.getStatus());
        vo.setPeakOnline(room.getPeakOnline());
        vo.setViewerTotal(room.getViewerTotal());
        vo.setStartTime(room.getStartTime());
        vo.setEndTime(room.getEndTime());
        if (room.isLiving()) {
            // 在线人数取 Redis 实时计数，库里的 peakOnline 只在关播时定格
            vo.setOnlineCount(danmakuService.onlineOf(room.getId()));
            // streamKey 关播后置空，直播中才拼得出播放地址与弹幕端点
            if (!isBlank(room.getStreamKey())) {
                vo.setPlayUrl(signedPlayUrl(room.getId(), room.getStreamKey()));
            }
            vo.setDanmakuWs(danmakuWsPath(room.getId()));
        } else {
            vo.setOnlineCount(0);
        }
        return vo;
    }

    /**
     * 生成签名播放地址：{@code {playBaseUrl}/{roomId}/{streamKey}/index.m3u8?expire=&sign=}。
     *
     * <p>签名只对 path 部分计算，且 path 必须与 Nginx 的 {@code $uri} 逐字一致——
     * 因此从 playBaseUrl 里解析出 path 前缀（含 location），再拼上目录与文件名。
     *
     * <p>摘要以 <b>base64url（无填充）</b>编码，与 {@code ngx_http_secure_link_module} 一致：
     * 该模块把 {@code secure_link_md5} 指定的串做 md5 后 base64url 编码再与 URL 上的 sign 比对，
     * 并在 expire 早于当前时间时判失效。用十六进制会永远对不上。
     */
    private String signedPlayUrl(Long roomId, String streamKey) {
        String relative = roomId + "/" + streamKey + "/index.m3u8";
        String full = joinUrl(properties.getPlayBaseUrl(), relative);
        String path = pathOf(full);
        long expire = System.currentTimeMillis() / 1000L + properties.getSignTtlSeconds();
        String raw = path + expire + properties.getSignSecret();
        // 必须与 Nginx secure_link 的编码一致：它把 md5 摘要（16 字节原始值）做 base64url（无填充），
        // 而不是十六进制。用十六进制的话 secure_link 比对永远失败，播放口会一律 403。
        byte[] digest = DigestUtils.md5Digest(raw.getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return full + "?expire=" + expire + "&sign=" + sign;
    }

    /** 从绝对 URL 中取出 path（含前导斜杠），解析失败时退回「去掉 scheme://host」的粗略切法 */
    private String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            int schemeEnd = url.indexOf("://");
            int pathStart = schemeEnd < 0 ? url.indexOf('/') : url.indexOf('/', schemeEnd + 3);
            return pathStart < 0 ? "" : url.substring(pathStart);
        }
    }

    /** 拼接基址与相对路径，吃掉基址尾部与相对头部的重复斜杠 */
    private String joinUrl(String base, String relative) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = relative.startsWith("/") ? relative.substring(1) : relative;
        return left + "/" + right;
    }

    private String randomStreamKey() {
        StringBuilder sb = new StringBuilder(STREAM_KEY_LENGTH);
        for (int i = 0; i < STREAM_KEY_LENGTH; i++) {
            sb.append(KEY_ALPHABET[RANDOM.nextInt(KEY_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 弹幕端点路径集中一处，避免 start 与 toVO 两处手拼漂移。
     * 只给路径与 query，ws(s)://host 前缀由前端按同源/Electron 环境补（见 utils/env.js）。
     */
    private static String danmakuWsPath(Long roomId) {
        return ImConstants.WS_LIVE_ENDPOINT + "?roomId=" + roomId;
    }
}
