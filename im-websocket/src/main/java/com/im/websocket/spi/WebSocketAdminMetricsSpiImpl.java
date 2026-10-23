package com.im.websocket.spi;

import com.im.common.domain.AdminMetricDTO;
import com.im.common.spi.AdminMetricsSpi;
import com.im.websocket.manager.WsSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接层贡献给后台看板的数字：此刻有多少人和多少条长连接挂在本实例上。
 *
 * <p>这两个数<b>只有 im-websocket 自己知道</b>：连接注册表是纯内存态，别的模块想报也只能猜。
 * 让它走 {@code AdminMetricsSpi} 而不是塞进后台的固定字段，是为了让「没装 websocket 模块」
 * 自然退化成一格缺失，而不是看板上出现一个永远的 0。
 *
 * <p><b>口径边界必须说清</b>：取的是<b>本进程</b>的连接注册表。单机部署时它就是全站在线数；
 * 一旦横向扩出多个实例，每个实例只看得见自己那一份，看板上会低报。真要跨实例统计，
 * 正解是把在线态收敛到 Redis（项目里 {@code OnlineStatusSpi} 已经有一份全局在线键），
 * 但那是「谁在线」用于消息路由的口径，和这里的「本实例负载」不是同一个问题，不该混成一个数字。
 *
 * <p>类名带模块前缀是必需的，原因见 {@code AdminMetricsSpi} 接口注释。
 */
@Service
@RequiredArgsConstructor
public class WebSocketAdminMetricsSpiImpl implements AdminMetricsSpi {

    private final WsSessionManager sessionManager;

    @Override
    public String source() {
        return "ws";
    }

    @Override
    public String sectionTitle() {
        return "连接";
    }

    @Override
    public List<AdminMetricDTO> metrics() {
        List<AdminMetricDTO> metrics = new ArrayList<>(2);
        metrics.add(metric("onlineUsers", "在线用户", (long) sessionManager.onlineUserCount(),
                "仅统计当前实例的连接注册表，单机部署即全站"));
        metrics.add(metric("wsConnections", "长连接数", (long) sessionManager.connectionCount(),
                "同一用户多端、同端多标签分别计数，恒不小于在线用户数"));
        return metrics;
    }

    private AdminMetricDTO metric(String key, String label, Long value, String hint) {
        return AdminMetricDTO.builder()
                .key(key)
                .label(label)
                .value(value)
                .hint(hint)
                .build();
    }
}
