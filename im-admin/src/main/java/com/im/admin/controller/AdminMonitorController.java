package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.vo.MonitorOverviewVO;
import com.im.admin.service.AdminMonitorService;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的监控告警页取数（三期）。
 *
 * <p>与 actuator 的分工：{@code /actuator/prometheus}（management 端口，供 Prometheus 抓取）
 * 与 {@code /actuator/health} 是<b>机器消费</b>的通道，本控制器是<b>人看的</b>那一层——
 * 后台页面拿不到 management 端口（默认只绑 127.0.0.1，浏览器在管理员的电脑上），
 * 所以在这里经 MeterRegistry 把同一批指标转成 JSON。两条通道读的是同一个 registry，
 * 不存在「页面说健康、Prometheus 说宕了」的双账本。
 *
 * <p>不做缓存也不做轮询端点：快照即取即得（内存读为主），前端手动刷新。
 * 自动刷新是二期之后的事——先把「看得懂的一屏」做对，再谈「不用点刷新」。
 */
@Tag(name = "16-管理后台", description = "监控告警：JVM/HTTP/连接池指标、健康检查与慢接口")
@RestController
@RequestMapping("/api/admin/monitor")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_MONITOR)
public class AdminMonitorController {

    private final AdminMonitorService adminMonitorService;

    @Operation(summary = "监控总览",
            description = "JVM 内存/线程/CPU、HTTP 请求量与 P95/P99、HikariCP 池、actuator 健康聚合、"
                    + "慢接口统计与最近明细；取不到的指标为 null（前端显示「—」而不是 0）")
    @GetMapping("/overview")
    public Result<MonitorOverviewVO> overview() {
        return Result.ok(adminMonitorService.overview());
    }

    @Operation(summary = "慢接口明细",
            description = "只回慢接口分组：阈值、累计条数与最近 N 条明细，比整屏总览便宜，供高频刷新")
    @GetMapping("/slow")
    public Result<MonitorOverviewVO.Slow> slow() {
        return Result.ok(adminMonitorService.slowOnly());
    }
}
