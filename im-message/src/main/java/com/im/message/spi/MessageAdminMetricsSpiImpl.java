package com.im.message.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.domain.AdminMetricDTO;
import com.im.common.spi.AdminMetricsSpi;
import com.im.message.entity.Message;
import com.im.message.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息模块贡献给后台看板的数字。
 *
 * <p><b>只报增量，不报总量</b>：{@code im_message} 是全库最大的一张表，
 * {@code SELECT COUNT(*)} 在 InnoDB 上是把二级索引整页扫一遍，行数上千万时一次查询就是几秒起步，
 * 而看板是每次刷新都要算的——后台那 60 秒缓存拦不住「每小时被打开十次」的持续压力。
 * 所以两条指标都带 {@code send_time} 范围，走 {@code idx_send_time}，代价与时间窗口成正比、与表总量无关。
 *
 * <p>什么时候需要总量（比如「平台累计消息数」）：正解是建一张按日汇总表让离线任务累加，
 * 而不是在这里补一条全表 COUNT。这条取舍就是留给后来的待办。
 *
 * <p>类名带模块前缀是必需的，原因见 {@code AdminMetricsSpi} 接口注释。
 */
@Service
@RequiredArgsConstructor
public class MessageAdminMetricsSpiImpl implements AdminMetricsSpi {

    /** 趋势窗口（天），与后台其他分区同口径 */
    private static final int TREND_DAYS = 7;

    private final MessageMapper messageMapper;

    @Override
    public String source() {
        return "message";
    }

    @Override
    public String sectionTitle() {
        return "消息";
    }

    @Override
    public List<AdminMetricDTO> metrics() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = todayStart.minusDays(TREND_DAYS - 1L);
        List<AdminMetricDTO> metrics = new ArrayList<>(2);
        metrics.add(metric("todayMessages", "今日新增消息",
                messageMapper.selectCount(Wrappers.<Message>lambdaQuery()
                        .ge(Message::getSendTime, todayStart)),
                "按发送时间统计，口径为今日零点起，走 send_time 索引"));
        metrics.add(metric("weekMessages", "近 7 天新增消息",
                messageMapper.selectCount(Wrappers.<Message>lambdaQuery()
                        .ge(Message::getSendTime, weekStart)),
                "含今日，起点为 7 天前的零点；不含逻辑删除（消息表本就无删除标记）"));
        return metrics;
    }

    /**
     * 打包一格数字。取数失败时让异常冒到后台那一层去标记「数据获取失败」，
     * 这里不要把异常吞成 0——0 和「没查到」在看板上是两句话。
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
