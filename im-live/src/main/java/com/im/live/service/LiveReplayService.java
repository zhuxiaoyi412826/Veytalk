package com.im.live.service;

import com.im.common.api.ResultCode;
import com.im.common.constant.RedisKeys;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.live.config.LiveProperties;
import com.im.live.dto.po.LiveReplayPO;
import com.im.live.dto.req.LiveReplayRequest;
import com.im.live.dto.vo.LiveReplayVO;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 直播回放存档：<b>零 DDL、零新增媒体写入</b>的一版。
 *
 * <h2>为什么回放几乎是「免费」的</h2>
 *
 * <p>画面字节从推流那一刻起就已经在媒体服务器上了：ffmpeg 把 1 秒一片的 HLS 分片 PUT 上去，
 * 推流端的 {@code delete_segments} 清的是主播本机的临时目录。真正缺的是「一份不滚动的清单」：
 * 直播中的 {@code index.m3u8} 是 12 秒滑窗且 {@code omit_endlist}，永远看不了历史。
 * 所以关播时由推流端把整场的分片名定版成逐档 VOD 清单 + 回放主清单，PUT 到
 * {@code {roomId}/replay/}，回放就成立了（清单生成见 {@code electron/live.js}）。
 *
 * <h2>这个服务只管两件事</h2>
 *
 * <ul>
 *   <li><b>入口标记</b>：主播端上报成功后写 {@code im:live:replay:{roomId}}，
 *       房间列表/详情据此显示「看回放」；</li>
 *   <li><b>地址签发</b>：有人点进来时现签一份回放清单地址交给播放器。</li>
 * </ul>
 *
 * <p>两件事<b>都不决定回放能不能播</b>：清单在盘上就可播，Redis 整个清掉也只是没有入口。
 * 因此这里不需要事务、不需要补偿任务，也没有「键写成功但文件没了」这种不一致要处理。
 *
 * <h2>与画面寿命的关系（入口与文件共用一个保留期）</h2>
 *
 * <p>{@code im.live.replay.retention-days}（默认 <b>1 天</b>）同时是两件事的口径：本服务的 Redis
 * 入口 TTL，以及媒体侧删源文件的清理线。但删文件不在这个服务里做——控制面进不到媒体磁盘，
 * 那是媒体面的事，由 {@code electron/live-retention.js} 在媒体服务器所在机器上按目录最新 mtime 执行
 * （自测的 live-media-server.js 已内置周期调用）。两边不一致时的安全方向是
 * 「媒体侧不短于入口」：入口先过期只会少一个入口，文件先消失则会挂出一个点开就 404 的「看回放」
 * （机理与挂载命令见 md/直播.md §10.7）。
 *
 * <h2>已知边界（有意为之，不是遗漏）</h2>
 *
 * <ul>
 *   <li>回放只有 HLS 那一路：<b>连麦分屏的合成画面不进回放</b>，回看到的是主播推的那一路原始画面；</li>
 *   <li>源文件只留 {@code retention-days}（默认 1 天），到期后「入口 + 画面」一起消失；
 *       想长留就调大那个值（两处共一个环境变量），不想留回放就直接关总开关；</li>
 *   <li>硬编失败回落软编重开时，ffmpeg 的分片序号从 0 重来并覆盖同名文件，所以那种场次的回放
 *       <b>只覆盖重开之后的部分</b>。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveReplayService {

    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final LiveProperties properties;
    private final LiveRoomMapper roomMapper;
    private final LiveUrlSigner signer;

    /**
     * 主播上报「这一场有回放了」。可重复上报（重连、前端重试），后写覆盖前写。
     *
     * <p>校验的是「你有没有资格说这场直播有回放」，而不是「你说的事实不事实」——后者无从校对。
     */
    public void report(Long roomId, Long userId, LiveReplayRequest request) {
        requireEnabled();
        LiveRoom room = roomMapper.selectById(roomId);
        BusinessException.throwIf(room == null, ResultCode.LIVE_ROOM_NOT_FOUND);
        if (!room.managedBy(userId)) {
            throw new BusinessException(ResultCode.LIVE_FORBIDDEN);
        }
        // 封禁场不留回放入口：管理端断播的内容不该还能被回看（分片仍在盘上，但没有地址指向它）
        BusinessException.throwIf(room.isBanned(), ResultCode.LIVE_FORBIDDEN);

        int maxSeconds = Math.max(1, properties.getReplay().getMaxDurationHours()) * 3600;
        long duration = clamp(request.getDurationSeconds(), maxSeconds);
        int segments = request.getSegmentCount() == null || request.getSegmentCount() <= 0 ? 0
                : (int) Math.min(request.getSegmentCount(), (long) maxSeconds * 2);
        // 一片都没有就不写：否则前端挂出一个点开就 404 的「看回放」，比没有入口更糟
        BusinessException.throwIf(segments <= 0, ResultCode.LIVE_REPLAY_NOT_FOUND);

        LiveReplayPO po = LiveReplayPO.builder()
                .roomId(roomId)
                .anchorId(userId)
                .segmentCount(segments)
                .durationSeconds(duration)
                .bytes(request.getBytes() == null || request.getBytes() < 0 ? 0L : request.getBytes())
                .reportAt(System.currentTimeMillis())
                .build();
        redisUtil.set(RedisKeys.liveReplay(roomId), jsonUtil.toJson(po), retention());
        log.info("回放上报: roomId={}, segments={}, duration={}s, bytes={}",
                roomId, segments, duration, po.getBytes());
    }

    /**
     * 取回放：没有入口时返回 null（不抛错）。
     *
     * <p>前端只在 {@code hasReplay} 为真时才点得到这里，null 只可能是「入口刚过期」这种竞态，
     * 值得一次静默降级而不是一句「回放不存在」的弹窗。
     */
    public LiveReplayVO get(Long roomId) {
        LiveReplayPO po = load(roomId);
        if (po == null) {
            return null;
        }
        LiveReplayVO vo = new LiveReplayVO();
        vo.setRoomId(roomId);
        vo.setPlayUrl(signer.replayUrl(roomId));
        vo.setDurationSeconds(po.getDurationSeconds());
        vo.setSegmentCount(po.getSegmentCount());
        vo.setBytes(po.getBytes());
        vo.setReportAt(po.getReportAt());
        return vo;
    }

    /** 有没有回放入口。房间列表要逐行问一次，所以只 EXISTS 不取 JSON */
    public boolean has(Long roomId) {
        return roomId != null && properties.getReplay().isEnabled()
                && redisUtil.hasKey(RedisKeys.liveReplay(roomId));
    }

    /** 封禁场清入口：与 {@link #report} 里的拒绝成对，管住「先有回放后被封」那条路 */
    public void drop(Long roomId) {
        redisUtil.delete(RedisKeys.liveReplay(roomId));
    }

    /* ==================== 内部 ==================== */

    private LiveReplayPO load(Long roomId) {
        return jsonUtil.fromJsonQuietly(redisUtil.get(RedisKeys.liveReplay(roomId)), LiveReplayPO.class);
    }

    private void requireEnabled() {
        if (!properties.getReplay().isEnabled()) {
            throw new BusinessException(ResultCode.LIVE_REPLAY_DISABLED);
        }
    }

    private Duration retention() {
        return Duration.ofDays(Math.max(1, properties.getReplay().getRetentionDays()));
    }

    /** 夹到 [0, max]，null 与负数都按 0；上限之上不报错只截（见类注释的「无从校对」）。max 用秒做上限 */
    private long clamp(Long value, long max) {
        if (value == null || value < 0) {
            return 0L;
        }
        return Math.min(value, max);
    }
}
