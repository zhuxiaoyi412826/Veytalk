package com.im.live.spi;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminLiveDTO;
import com.im.common.domain.AdminLiveQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminLiveSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import com.im.live.service.LiveDanmakuService;
import com.im.live.service.LiveRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台直播管理契约的实现（{@link AdminLiveSpi}）。
 *
 * <p>强制关播只有 {@code LiveRoomService#stop(roomId, "ban")} 一个落点，理由写在
 * 接口注释里：举报处置走的是同一条路，关房逻辑（断弹幕、清心跳、作废门禁、删回放入口）
 * 分两处必出一起漏一。
 *
 * <p>排序用 {@code FIELD(status, 1, 2, 3)} 而不是裸按 start_time：管理员开这一页第一眼看
 * 的永远是「现在谁在播」，直播中的场次全库通常个位数，让它置顶值回票价。
 * 这里用 QueryWrapper 字符串列而非 Lambda 是因为函数排序表达式 Lambda 表达不了——
 * 串是本类私有常量，不来自任何外部参数，不存在注入面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveAdminSpiImpl implements AdminLiveSpi {

    /** 单页上限，与 VideoAdminSpiImpl 同口径 */
    private static final long MAX_PAGE_SIZE = 100;
    private static final int KEYWORD_MAX = 50;

    /** 关播原因码：ban 同时是「跳过主播归属校验」与「观众看到已被管理员中断」的开关 */
    private static final String END_REASON_BAN = "ban";

    private final LiveRoomMapper roomMapper;
    private final LiveRoomService liveRoomService;
    private final LiveDanmakuService danmakuService;
    private final UserQuerySpi userQuerySpi;

    @Override
    public PageResult<AdminLiveDTO> pageRooms(AdminLiveQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = TextUtil.isBlank(query.getKeyword()) ? null : query.getKeyword().trim();
        if (keyword != null && keyword.length() > KEYWORD_MAX) {
            keyword = keyword.substring(0, KEYWORD_MAX);
        }

        QueryWrapper<LiveRoom> wrapper = new QueryWrapper<LiveRoom>()
                .eq(query.getStatus() != null, "status", query.getStatus())
                .eq(query.getAnchorId() != null, "anchor_id", query.getAnchorId())
                .like(keyword != null, "title", keyword)
                .orderByAsc("FIELD(status, 1, 2, 3)")
                .orderByDesc("start_time");
        Page<LiveRoom> result = roomMapper.selectPage(new Page<>(current, size), wrapper);
        List<LiveRoom> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> anchorIds = new HashSet<>();
        for (LiveRoom room : records) {
            if (room.getAnchorId() != null) {
                anchorIds.add(room.getAnchorId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(anchorIds);
        List<AdminLiveDTO> dtos = new ArrayList<>(records.size());
        for (LiveRoom room : records) {
            dtos.add(toDTO(room, briefs.get(room.getAnchorId())));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminLiveDTO detail(Long roomId) {
        if (roomId == null) {
            return null;
        }
        LiveRoom room = roomMapper.selectById(roomId);
        if (room == null) {
            return null;
        }
        UserBriefDTO anchor = room.getAnchorId() == null ? null : userQuerySpi.getById(room.getAnchorId());
        return toDTO(room, anchor);
    }

    @Override
    public void forceStop(Long roomId, Long operatorId) {
        LiveRoom room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new BusinessException(ResultCode.LIVE_ROOM_NOT_FOUND);
        }
        if (!room.isLiving()) {
            // 幂等：主播已自己关播是最常见情况，不报错（语义对齐 ReportRoomTargetSpiImpl#dispose）
            log.info("[后台直播管理] operator={} 强制关播跳过（房间 {} 状态={}，已不在直播中）",
                    operatorId, roomId, room.getStatus());
            return;
        }
        liveRoomService.stop(roomId, END_REASON_BAN);
        log.info("[后台直播管理] operator={} 强制关播: roomId={}, anchorId={}, title={}, peakOnline={}",
                operatorId, roomId, room.getAnchorId(), room.getTitle(), room.getPeakOnline());
    }

    private AdminLiveDTO toDTO(LiveRoom room, UserBriefDTO anchor) {
        String name = anchor == null ? "已注销用户"
                : (TextUtil.isNotBlank(anchor.getNickname()) ? anchor.getNickname() : anchor.getUsername());
        AdminLiveDTO.AdminLiveDTOBuilder builder = AdminLiveDTO.builder()
                .roomId(room.getId())
                .anchorId(room.getAnchorId())
                .anchorName(name)
                .title(room.getTitle())
                .cover(room.getCover())
                .notice(room.getNotice())
                .sourceType(room.getSourceType())
                .resolution(room.getResolution())
                .status(room.getStatus())
                .peakOnline(room.getPeakOnline())
                .viewerTotal(room.getViewerTotal())
                .startTime(room.getStartTime())
                .endTime(room.getEndTime())
                .endReason(room.getEndReason());
        if (room.isLiving()) {
            // 在线数取 Redis 实时值（与用户端 detail 同一个来源），非直播中不给假实时
            builder.onlineNow(danmakuService.onlineOf(room.getId()));
        } else {
            builder.onlineNow(0);
        }
        return builder.build();
    }
}
