package com.im.live.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.domain.AdminMetricDTO;
import com.im.common.spi.AdminMetricsSpi;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播模块贡献给后台看板的数字。
 *
 * <p>三条指标都走 {@code idx_status_start (status, start_time)}：
 * 在播数与封禁数只夹 {@code status} 一个等值条件，用得上索引最左前缀；
 * 「近 7 天已结束场次」刻意写成 {@code status IN (2,3) AND start_time >= ?}——
 * 只按 {@code start_time} 范围查是<b>用不到</b>这条索引的（它是复合索引，时间在后），
 * 加上 status 前缀反而能让优化器按每个状态值各做一段范围扫。
 *
 * <p>没有「累计场次」这类总量指标：{@code im_live_room} 一行就是一场直播，量级随开播次数线性增长，
 * 现在全表 COUNT 还便宜，但和消息表一样的道理——要看趋势就该按日汇总，而不是每次刷新看板都扫一遍。
 *
 * <p>类名带模块前缀是必需的，原因见 {@code AdminMetricsSpi} 接口注释。
 */
@Service
@RequiredArgsConstructor
public class LiveAdminMetricsSpiImpl implements AdminMetricsSpi {

    /** 趋势窗口（天），与后台其他分区同口径 */
    private static final int TREND_DAYS = 7;

    private final LiveRoomMapper roomMapper;

    @Override
    public String source() {
        return "live";
    }

    @Override
    public String sectionTitle() {
        return "直播";
    }

    @Override
    public List<AdminMetricDTO> metrics() {
        LocalDateTime weekStart = LocalDate.now().atStartOfDay().minusDays(TREND_DAYS - 1L);
        List<AdminMetricDTO> metrics = new ArrayList<>(3);
        metrics.add(metric("livingRooms", "正在直播",
                roomMapper.selectCount(Wrappers.<LiveRoom>lambdaQuery()
                        .eq(LiveRoom::getStatus, LiveRoom.STATUS_LIVING)),
                "当前状态为直播中的场次，含聊天室"));
        metrics.add(metric("weekEndedRooms", "近 7 天已结束",
                roomMapper.selectCount(Wrappers.<LiveRoom>lambdaQuery()
                        .in(LiveRoom::getStatus, LiveRoom.STATUS_ENDED, LiveRoom.STATUS_BANNED)
                        .ge(LiveRoom::getStartTime, weekStart)),
                "按开播时间统计，含主播关播与心跳超时，不含仍在播的场次"));
        metrics.add(metric("bannedRooms", "被中断的直播",
                roomMapper.selectCount(Wrappers.<LiveRoom>lambdaQuery()
                        .eq(LiveRoom::getStatus, LiveRoom.STATUS_BANNED)),
                "由举报处置或管理端强制断播的场次累计"));
        return metrics;
    }

    /**
     * 打包一格数字。取数失败让异常冒到后台那一层显示「数据获取失败」，不吞成 0。
     */
    private AdminMetricDTO metric(String key, String label, Long value, String hint) {
        return AdminMetricDTO.builder()
                .key(key)
                .label(label)
                .value(value)
                .hint(hint)
                .build();
    }
}
