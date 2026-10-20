package com.im.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.RedisUtil;
import com.im.live.dto.po.GiftAggPO;
import com.im.live.dto.vo.LiveAnchorStatsVO;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.GiftRecordMapper;
import com.im.live.mapper.LiveRoomMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主播数据面板的读侧聚合。
 *
 * <p><b>零 DDL，而且一个新键都不加</b>：数据全部来自既有的三处——
 * {@code im_live_room}（每场一行的定格值）、{@code im_gift_record}（送礼流水，两个索引刚好对得上
 * 面板的两种聚合维度）、Redis 里已有的在线计数与点赞热度。为此新增的唯一方法是
 * {@link LiveDanmakuService#peekStats}（把原本只有关播才读的内存统计只读地看一眼）。
 *
 * <p>刻意<b>没有</b>做的事，都不是忘了而是权衡后的取舍：
 * <ul>
 *   <li><b>不建汇总表</b>：面板只读「一个主播的最近 100 场」，聚合规模小到可以忽略，
 *       而汇总表要引入刷新时机与不一致窗口，代价远大于收益。真到了需要它的规模，
 *       刷新口径（增量还是定时全量）必须先写进文档再动手。</li>
 *   <li><b>不提供历史点赞与弹幕条数</b>：点赞只在 Redis 活 24 小时（{@code HEAT_TTL}），
 *       弹幕条数服务端从来没存过量。要做就得改写入链路落库，那是 DDL 决策，不该顺手加。</li>
 *   <li><b>不算分成、不发钱</b>：现有礼物体系只扣送礼人，主播收不到积分
 *       （见 {@code GiftServiceImpl.sendGift}）。面板上的礼物价值纯粹是观感指标。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveAnchorStatsService {

    /** 概览与逐场最多取多少场。多取一条只用来判定「是否被截断」，不额外发一次 COUNT */
    private static final int SESSION_MAX = 100;
    /** 金主榜取前几名 */
    private static final int GIFTER_MAX = 10;
    /** 礼物种类榜取前几名 */
    private static final int GIFT_TYPE_MAX = 8;
    /** 默认统计窗口（天）：月度复盘是主播最常见的问法 */
    private static final int DEFAULT_WINDOW_DAYS = 30;
    /** 窗口上限（天）：放开到一年就要扫一年的流水，面板不该允许这种查询 */
    private static final int MAX_WINDOW_DAYS = 366;

    private final LiveRoomMapper roomMapper;
    private final GiftRecordMapper giftRecordMapper;
    private final LiveDanmakuService danmakuService;
    private final LiveReplayService replayService;
    private final UserQuerySpi userQuerySpi;
    private final RedisUtil redisUtil;

    /**
     * 组装面板数据。
     *
     * @param anchorId 主播本人（接口层已从登录态取，不接受前端传别人的 ID）
     * @param days     统计窗口天数，越界就地夹到 {@code [1, }{@value #MAX_WINDOW_DAYS}{@code ]}
     */
    public LiveAnchorStatsVO stats(Long anchorId, Integer days) {
        int window = days == null || days <= 0 ? DEFAULT_WINDOW_DAYS : Math.min(days, MAX_WINDOW_DAYS);
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        // 窗口内的场次，倒序取 SESSION_MAX + 1 条：第 101 条只用来知道「还有更多没算进来」
        List<LiveRoom> fetched = roomMapper.selectList(new LambdaQueryWrapper<LiveRoom>()
                .eq(LiveRoom::getAnchorId, anchorId)
                .ge(LiveRoom::getStartTime, since)
                .orderByDesc(LiveRoom::getStartTime)
                .last("limit " + (SESSION_MAX + 1)));
        boolean capped = fetched.size() > SESSION_MAX;
        List<LiveRoom> rooms = capped ? fetched.subList(0, SESSION_MAX) : fetched;

        LiveAnchorStatsVO vo = new LiveAnchorStatsVO();
        vo.setWindowDays(window);
        vo.setSessionLimit(SESSION_MAX);
        vo.setSessionsCapped(capped);
        vo.setSessions(buildSessions(rooms));
        vo.setOverview(buildOverview(vo.getSessions(), rooms, anchorId, since));
        vo.setTopGifters(buildTopGifters(anchorId, since));
        vo.setTopGiftTypes(buildTopGiftTypes(anchorId, since));
        vo.setCurrent(buildCurrent(rooms, vo.getSessions()));
        return vo;
    }

    /* ==================== 逐场 ==================== */

    private List<LiveAnchorStatsVO.Session> buildSessions(List<LiveRoom> rooms) {
        if (rooms.isEmpty()) {
            return List.of();
        }
        Set<Long> roomIds = new HashSet<>(rooms.size());
        for (LiveRoom room : rooms) {
            roomIds.add(room.getId());
        }
        // 一次 IN 拿全部场的礼物聚合，缺行按 0 处理（没收过礼的场本来就没有流水）
        Map<Long, GiftAggPO.Room> aggs = giftAggOf(roomIds);

        List<LiveAnchorStatsVO.Session> sessions = new ArrayList<>(rooms.size());
        for (LiveRoom room : rooms) {
            LiveAnchorStatsVO.Session item = new LiveAnchorStatsVO.Session();
            item.setRoomId(room.getId());
            item.setTitle(room.getTitle());
            item.setCover(room.getCover());
            item.setStatus(room.getStatus());
            item.setLiving(room.isLiving());
            item.setStartTime(room.getStartTime());
            item.setEndTime(room.getEndTime());
            item.setDurationSeconds(secondsOf(room.getStartTime(), room.getEndTime()));
            item.setPeakOnline(nz(room.getPeakOnline()));
            item.setViewerTotal(nz(room.getViewerTotal()));
            item.setEndReason(room.getEndReason());
            GiftAggPO.Room agg = aggs.get(room.getId());
            item.setGiftPrice(agg == null ? 0L : nz(agg.getTotalPrice()));
            item.setGiftCount(agg == null ? 0L : nz(agg.getRecordCount()));
            item.setGiftSenderCount(agg == null ? 0L : nz(agg.getSenderCount()));
            // 直播中的场不可能有回放（清单是关播才定版 PUT 的），不必去问 Redis
            item.setHasReplay(!room.isLiving() && replayService.has(room.getId()));
            sessions.add(item);
        }
        return sessions;
    }

    /** 按房间聚合礼物：空集合必须先挡，{@code IN ()} 是语法错误 */
    private Map<Long, GiftAggPO.Room> giftAggOf(Collection<Long> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        List<GiftAggPO.Room> rows = giftRecordMapper.aggregateByRooms(roomIds);
        Map<Long, GiftAggPO.Room> map = new HashMap<>(rows.size());
        for (GiftAggPO.Room row : rows) {
            map.put(row.getRoomId(), row);
        }
        return map;
    }

    /* ==================== 概览 ==================== */

    private LiveAnchorStatsVO.Overview buildOverview(List<LiveAnchorStatsVO.Session> sessions,
                                                     List<LiveRoom> rooms,
                                                     Long anchorId,
                                                     LocalDateTime since) {
        LiveAnchorStatsVO.Overview overview = new LiveAnchorStatsVO.Overview();
        long totalDuration = 0L;
        long totalGift = 0L;
        long totalRecords = 0L;
        int totalViewer = 0;
        int totalPeak = 0;
        int maxPeak = 0;
        for (LiveAnchorStatsVO.Session item : sessions) {
            totalDuration += nz(item.getDurationSeconds());
            totalGift += nz(item.getGiftPrice());
            totalRecords += nz(item.getGiftCount());
            totalViewer += nz(item.getViewerTotal());
            totalPeak += nz(item.getPeakOnline());
            maxPeak = Math.max(maxPeak, nz(item.getPeakOnline()));
        }
        overview.setTotalSessions(sessions.size());
        overview.setTotalDurationSeconds(totalDuration);
        overview.setTotalViewer(totalViewer);
        // 按场简单平均而非按秒加权：主播问的是「我一般能撑到多少人在看」，
        // 加权平均会被那场挂了一整夜没人看的直播把数拉平，反而不像人话
        overview.setAvgPeakOnline(sessions.isEmpty() ? 0 : (int) Math.round(totalPeak / (double) sessions.size()));
        overview.setMaxPeakOnline(maxPeak);
        overview.setTotalGiftPrice(totalGift);
        overview.setGiftRecordCount(totalRecords);
        // 送礼人数按整个窗口去重，不等于逐场 senderCount 相加（同一个人跨场送过只算一个）
        Long senders = giftRecordMapper.countSenders(anchorId, since);
        overview.setGiftSenderCount(senders == null ? 0L : senders);
        // 列表是开播时间倒序，第一条即最近一场；一场都没有则 rooms 为空
        overview.setLastLiveAt(rooms.isEmpty() ? null : rooms.get(0).getStartTime());
        return overview;
    }

    /* ==================== 榜单 ==================== */

    private List<LiveAnchorStatsVO.Gifter> buildTopGifters(Long anchorId, LocalDateTime since) {
        List<GiftAggPO.Gifter> rows = giftRecordMapper.selectTopGifters(anchorId, since, GIFTER_MAX);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> senderIds = new HashSet<>(rows.size());
        for (GiftAggPO.Gifter row : rows) {
            senderIds.add(row.getSenderId());
        }
        // 昵称头像走 SPI 批量取，不在 SQL 里 join im_user（星型依赖：直播模块不认识用户模块的表）
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(senderIds);
        List<LiveAnchorStatsVO.Gifter> list = new ArrayList<>(rows.size());
        for (GiftAggPO.Gifter row : rows) {
            LiveAnchorStatsVO.Gifter item = new LiveAnchorStatsVO.Gifter();
            item.setSenderId(row.getSenderId());
            UserBriefDTO user = users.get(row.getSenderId());
            // 查不到只有两种可能：账号已注销（级联清掉了资料）或数据本身是脏的。
            // 都不该让整块榜单消失，标成「未知用户」继续显示——主播在意的是那笔积分，不是这个 ID
            item.setSenderName(user == null || user.getNickname() == null ? "未知用户" : user.getNickname());
            item.setSenderAvatar(user == null ? null : user.getAvatar());
            item.setTotalPrice(nz(row.getTotalPrice()));
            item.setRecordCount(nz(row.getRecordCount()));
            item.setLastAt(row.getLastAt());
            list.add(item);
        }
        return list;
    }

    private List<LiveAnchorStatsVO.GiftType> buildTopGiftTypes(Long anchorId, LocalDateTime since) {
        List<GiftAggPO.Type> rows = giftRecordMapper.selectTopGiftTypes(anchorId, since, GIFT_TYPE_MAX);
        List<LiveAnchorStatsVO.GiftType> list = new ArrayList<>(rows.size());
        for (GiftAggPO.Type row : rows) {
            LiveAnchorStatsVO.GiftType item = new LiveAnchorStatsVO.GiftType();
            item.setGiftId(row.getGiftId());
            item.setGiftName(row.getGiftName());
            item.setGiftIcon(row.getGiftIcon());
            item.setTotalCount(nz(row.getTotalCount()));
            item.setTotalPrice(nz(row.getTotalPrice()));
            list.add(item);
        }
        return list;
    }

    /* ==================== 本场实时 ==================== */

    /**
     * 此刻正在播的那场。列表里最多一场是 LIVING（{@code maxLivingRoomsPerUser} 默认 1，
     * 且即使配大了，倒序取到的第一场也是最新开播的），所以找到就停。
     *
     * @param sessions 已经算好的逐场列表，用来取本场礼物（与逐场同一口径，不让同一个数出现两个值）
     */
    private LiveAnchorStatsVO.Current buildCurrent(List<LiveRoom> rooms,
                                                   List<LiveAnchorStatsVO.Session> sessions) {
        LiveRoom living = null;
        for (LiveRoom room : rooms) {
            if (room.isLiving()) {
                living = room;
                break;
            }
        }
        if (living == null) {
            return null;
        }
        LiveAnchorStatsVO.Current current = new LiveAnchorStatsVO.Current();
        current.setRoomId(living.getId());
        current.setOnlineCount(danmakuService.onlineOf(living.getId()));
        int[] stat = danmakuService.peekStats(living.getId());
        current.setPeakOnline(stat[0]);
        current.setViewerTotal(stat[1]);
        current.setDurationSeconds(secondsOf(living.getStartTime(), LocalDateTime.now()));
        for (LiveAnchorStatsVO.Session item : sessions) {
            if (living.getId().equals(item.getRoomId())) {
                current.setGiftPrice(item.getGiftPrice());
                break;
            }
        }
        // 只有点赞没有别的来源：热度键 TTL 24 小时，取不到就当 0（可能是重启过，也可能确实没人点）
        current.setLikeHeat(counter(RedisKeys.liveLikeHeat(living.getId())));
        return current;
    }

    /* ==================== 小工具 ==================== */

    /**
     * 一段直播的秒数。缺开始时间按 0；没结束时间按「此刻」算（直播中）；
     * 负数只可能来自脏数据或时钟回拨，截成 0 而不是往面板上贴一个负时长。
     */
    private Long secondsOf(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            return 0L;
        }
        long seconds = Duration.between(start, end == null ? LocalDateTime.now() : end).getSeconds();
        return Math.max(0L, seconds);
    }

    /** Redis 计数器读取：脏值与缺失都按 0，面板不该因为一个坏值整块打不开 */
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

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
