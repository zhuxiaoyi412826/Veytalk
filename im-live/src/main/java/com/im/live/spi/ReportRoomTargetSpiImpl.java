package com.im.live.spi;

import com.im.common.domain.ReportDisposeCmd;
import com.im.common.domain.ReportDisposeResult;
import com.im.common.domain.ReportTargetDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ReportDisposition;
import com.im.common.enums.ReportTargetType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ReportTargetSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import com.im.live.service.LiveRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「举报一场直播」的证据解析与中断执行。
 *
 * <h2>能存的只有元数据</h2>
 *
 * <p>直播的内容是流，不是一条可以被摘下来的记录：举报进来时那一幕已经过去了。
 * 所以证据里放的是<b>判定所需的最小元数据</b>（主播、状态、开播时长、峰值在线、关播原因），
 * {@code excerpt} 取房间公告——它是主播自己写的、最可能违规且能被固化的文本。
 * 真正需要的画面证据得靠录播件（{@code im_live_video}），一期不做把录制文件挂进工单，
 * 因为那会牵出回放签名地址的授权问题，不是一期该背的复杂度。
 *
 * <p>{@code streamKey} <b>绝不进证据</b>：它是这场直播的能力凭证，工单表是可长期留存、
 * 可被多个管理员翻阅的地方，把凭证抄进去等于给一张过期票据留了个可用的下载地址。
 *
 * <h2>中断走 stop 的 ban 分支</h2>
 *
 * <p>{@link LiveRoomService#stop} 天生满足处置要求：房间不存在抛异常、不在直播中静默返回、
 * ban 分支跳过「必须是主播本人」的归属校验。因此这里不另写一套关房逻辑
 * （两处关房，迟早一处断了弹幕通道另一处忘了清心跳键）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRoomTargetSpiImpl implements ReportTargetSpi {

    /** 公告摘要本地限长，后台落库前还会再夹一道兜底 */
    private static final int EXCERPT_MAX = 200;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 关播原因码。{@code LiveRoomService#stop} 的三个原因（stop / timeout / ban）在直播模块内
     * 一直都是字面串（没枚举也没有常量），本类只取 {@code ban}：它同时是
     * 「跳过主播归属校验」与「观众看到已被管理员中断」文案的开关。
     */
    private static final String END_REASON_BAN = "ban";

    private final LiveRoomMapper roomMapper;
    private final LiveRoomService liveRoomService;
    private final UserQuerySpi userQuerySpi;

    @Override
    public String targetType() {
        return ReportTargetType.ROOM.getCode();
    }

    @Override
    public ReportTargetDTO resolve(Long targetId) {
        if (targetId == null) {
            return null;
        }
        LiveRoom room = roomMapper.selectById(targetId);
        if (room == null) {
            // abort（撤销从未推流的场次）会真删行，所以这里确实可能查不到；返回 null 让提交侧报 14001
            return null;
        }
        UserBriefDTO anchor = room.getAnchorId() == null ? null : userQuerySpi.getById(room.getAnchorId());
        String anchorName = anchor == null ? "已注销主播"
                : (TextUtil.isNotBlank(anchor.getNickname()) ? anchor.getNickname() : anchor.getUsername());

        Map<String, Object> meta = new LinkedHashMap<>(8);
        meta.put("主播", anchorName);
        meta.put("房间状态", statusText(room));
        meta.put("开播时间", format(room.getStartTime()));
        meta.put("直播时长", durationText(room));
        meta.put("峰值在线", String.valueOf(room.getPeakOnline() == null ? 0 : room.getPeakOnline()));
        meta.put("累计观看", String.valueOf(room.getViewerTotal() == null ? 0 : room.getViewerTotal()));
        if (!room.isLiving()) {
            meta.put("结束原因", TextUtil.isBlank(room.getEndReason()) ? "—" : liveRoomService.endReasonText(room.getEndReason()));
        }
        return ReportTargetDTO.builder()
                .targetType(ReportTargetType.ROOM.getCode())
                .targetId(room.getId())
                .reportedUserId(room.getAnchorId())
                .title(TextUtil.isBlank(room.getTitle()) ? "直播 #" + room.getId() : room.getTitle())
                .excerpt(TextUtil.summary(room.getNotice(), EXCERPT_MAX))
                .meta(meta)
                .dispositions(supportedDispositions())
                .build();
    }

    @Override
    public ReportDisposeResult dispose(ReportDisposeCmd cmd) {
        ReportDisposition action = ReportDisposition.of(cmd.getAction());
        if (action != ReportDisposition.CLOSE_ROOM) {
            return ReportDisposeResult.fail("直播模块不能执行动作「" + cmd.getAction() + "」");
        }
        LiveRoom room = roomMapper.selectById(cmd.getTargetId());
        if (room == null) {
            return ReportDisposeResult.fail("该场直播已不存在（可能被主播撤销）");
        }
        if (!room.isLiving()) {
            // 幂等：工单排队期间主播已经自己关播了，这是最常见的情况，不该报失败
            return ReportDisposeResult.ok("这场直播已经结束（" + statusText(room) + "），无需再中断");
        }
        try {
            liveRoomService.stop(room.getId(), END_REASON_BAN);
        } catch (BusinessException e) {
            return ReportDisposeResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[举报处置] 中断直播异常: roomId={}, err={}", room.getId(), e.getMessage());
            return ReportDisposeResult.fail("中断直播失败：" + e.getMessage());
        }
        return ReportDisposeResult.ok("已中断直播并断流，主播需重新开播");
    }

    private String statusText(LiveRoom room) {
        if (room.isLiving()) {
            return "直播中";
        }
        if (room.isBanned()) {
            return "已封禁";
        }
        return room.isEnded() ? "已结束" : "状态未知";
    }

    /**
     * 时长口径：仍在直播的算到当前时刻，已结束的算到关播时刻。
     * {@code startTime} 为空属于脏数据（开播必写），此时宁可给「—」也不要显示一个 0 分钟。
     */
    private String durationText(LiveRoom room) {
        LocalDateTime from = room.getStartTime();
        if (from == null) {
            return "—";
        }
        LocalDateTime to = room.isLiving() || room.getEndTime() == null ? LocalDateTime.now() : room.getEndTime();
        Duration duration = Duration.between(from, to);
        if (duration.isNegative()) {
            return "—";
        }
        long minutes = duration.toMinutes();
        long hours = minutes / 60;
        return hours > 0 ? hours + " 小时 " + (minutes % 60) + " 分钟" : minutes + " 分钟";
    }

    private String format(LocalDateTime time) {
        return time == null ? "—" : TIME_FORMAT.format(time);
    }
}
