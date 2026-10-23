package com.im.admin.service;

import com.im.admin.config.AdminProperties;
import com.im.admin.dto.vo.MonitorOverviewVO;
import com.im.admin.monitor.SlowRequestInterceptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控总览取数：把 MeterRegistry / actuator HealthEndpoint / 慢接口拦截器三个来源
 * 拼成一屏给后台监控页。
 *
 * <h2>数据从哪来</h2>
 * <ul>
 *   <li>{@code jvm.* / process.* / system.* / hikaricp.*} 由 actuator 的自动指标绑定注册，
 *       本类只按名字读现值——名字取不到就返回 null（显示「—」），<b>绝不补 0</b>；</li>
 *   <li>{@code http.server.requests} 的 P95/P99 默认是关的（micrometer 不留分位数以省内存），
 *       构造时用 MeterFilter 显式打开。放在构造函数而不是等首次请求：filter 只对
 *       <b>之后注册的 timer</b> 生效，而 timer 按 uri 模板惰性注册——服务一启动就有流量、
 *       filter 后放就来不及了。Boot 里 Tomcat 在容器就绪后才接流量，构造期是安全的；</li>
 *   <li>健康状态直接调 actuator 的 {@code HealthEndpoint} bean 而不是 HTTP 自环
 *       （自环 localhost:8081 依赖 management 端口配置，测试环境与生产不一致）。</li>
 * </ul>
 *
 * <p>本接口是「读一次」的快照，不做缓存：它就是给告警排障时按刷新用的，
 * 60 秒缓存在这里只会让人对着旧数据找不存在的问题。取数全部走内存里的 registry，
 * 唯一的 IO 是 health 聚合里的 db SELECT 1 与 diskStat，代价可控。
 */
@Slf4j
@Service
public class AdminMonitorService {

    /** 逐个给出的健康组件；不在册的（模块没装配）直接缺席，不显示成 DOWN 吓人 */
    private static final List<String> HEALTH_COMPONENTS = List.of("db", "redis", "ping", "diskSpace");

    private final MeterRegistry registry;
    private final SlowRequestInterceptor slowRequestInterceptor;
    private final AdminProperties adminProperties;
    private final ObjectProvider<HealthEndpoint> healthEndpointProvider;

    public AdminMonitorService(MeterRegistry registry,
                               SlowRequestInterceptor slowRequestInterceptor,
                               AdminProperties adminProperties,
                               ObjectProvider<HealthEndpoint> healthEndpointProvider) {
        this.registry = registry;
        this.slowRequestInterceptor = slowRequestInterceptor;
        this.adminProperties = adminProperties;
        this.healthEndpointProvider = healthEndpointProvider;
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if ("http.server.requests".equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentiles(0.50, 0.95, 0.99)
                            .expiry(Duration.ofMinutes(2))
                            .bufferLength(3)
                            .build()
                            .merge(config);
                }
                return config;
            }
        });
    }

    public MonitorOverviewVO overview() {
        return MonitorOverviewVO.builder()
                .collectedAt(LocalDateTime.now())
                .uptimeSeconds(uptimeSeconds())
                .jvm(jvm())
                .http(http())
                .slow(slow())
                .pool(pool())
                .health(health())
                .build();
    }

    /** 慢接口明细单独给端点：监控页轮询这个比整屏 overview 便宜 */
    public MonitorOverviewVO.Slow slowOnly() {
        return slow();
    }

    private Long uptimeSeconds() {
        Double gauge = gaugeValue("process.uptime", null);
        if (gauge != null) {
            return (long) gauge.doubleValue();
        }
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    private MonitorOverviewVO.Jvm jvm() {
        Double heapUsed = gaugeValue("jvm.memory.used", Map.of("area", "heap"));
        Double heapMax = gaugeValue("jvm.memory.max", Map.of("area", "heap"));
        Double nonHeapUsed = gaugeValue("jvm.memory.used", Map.of("area", "nonheap"));
        Double threads = gaugeValue("jvm.threads.live", null);
        Double cores = gaugeValue("system.cpu.count", null);
        Double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        Double processCpu = processCpuLoad();
        return MonitorOverviewVO.Jvm.builder()
                .heapUsedMb(toMb(heapUsed))
                .heapMaxMb(toMb(heapMax))
                .heapPct(heapUsed != null && heapMax != null && heapMax > 0
                        ? round1(heapUsed / heapMax * 100) : null)
                .nonHeapUsedMb(toMb(nonHeapUsed))
                .threadCount(threads == null ? null : (int) threads.longValue())
                .cpuCores(cores == null ? null : (int) cores.longValue())
                .systemLoadAverage(load < 0 ? null : round2(load))
                .processCpuPct(processCpu)
                .build();
    }

    /**
     * 进程 CPU 占比只有 JDK 的实现类（com.sun.management.OperatingSystemMXBean）提供，
     * 标准接口没有这个方法——instanceof 不上就返回 null，监控页显示「—」。
     * 反射名硬编码在 instanceof 里是 JDK 的公开承诺（jdk.management 模块导出该类）。
     */
    private Double processCpuLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double load = sun.getProcessCpuLoad();
            return load < 0 ? null : round2(load);
        }
        return null;
    }

    private MonitorOverviewVO.Http http() {
        Collection<Timer> timers = registry.find("http.server.requests").timers();
        if (timers.isEmpty()) {
            return null;
        }
        long count = 0;
        long serverError = 0;
        double sumMs = 0;
        double worstP95 = 0;
        double worstP99 = 0;
        double worstP50 = 0;
        for (Timer timer : timers) {
            count += timer.count();
            sumMs += timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
            if ("SERVER_ERROR".equals(timer.getId().getTag("outcome"))) {
                serverError += timer.count();
            }
            // 分位数是「每个 uri 模板各有一份」的估算值；总览页取最慢的那把尺子，
            // 平均值会被高频快接口稀释到看不见问题，最差 P95 才配得上「一屏总览」。
            // 未注册 percentile 的 timer 返回 NaN（而不是 0），用 isFinite 筛掉。
            worstP50 = Math.max(worstP50, finiteOrZero(timer.percentile(0.50, java.util.concurrent.TimeUnit.MILLISECONDS)));
            worstP95 = Math.max(worstP95, finiteOrZero(timer.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS)));
            worstP99 = Math.max(worstP99, finiteOrZero(timer.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS)));
        }
        return MonitorOverviewVO.Http.builder()
                .requestCount(count)
                .serverErrorCount(serverError)
                .avgMs(count == 0 ? null : round2(sumMs / count))
                .p50Ms(toMs(worstP50))
                .p95Ms(toMs(worstP95))
                .p99Ms(toMs(worstP99))
                .build();
    }

    private double finiteOrZero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private MonitorOverviewVO.Slow slow() {
        return MonitorOverviewVO.Slow.builder()
                .thresholdMs(adminProperties.getSlowRequestMillis())
                .slowTotal(slowRequestInterceptor.slowTotal())
                .recent(slowRequestInterceptor.recent())
                .build();
    }

    private MonitorOverviewVO.Pool pool() {
        Double active = gaugeValue("hikaricp.connections.active", null);
        Double max = gaugeValue("hikaricp.connections.max", null);
        if (active == null && max == null) {
            // 指标没注册（actuator 之外还有版本差异）时整个分组缺席，好过画一排 0
            return null;
        }
        Double idle = gaugeValue("hikaricp.connections.idle", null);
        Double pending = gaugeValue("hikaricp.connections.pending", null);
        return MonitorOverviewVO.Pool.builder()
                .active(active == null ? null : (int) active.longValue())
                .idle(idle == null ? null : (int) idle.longValue())
                .max(max == null ? null : (int) max.longValue())
                .pending(pending == null ? null : (int) pending.longValue())
                .build();
    }

    private MonitorOverviewVO.Health health() {
        HealthEndpoint endpoint = healthEndpointProvider.getIfAvailable();
        if (endpoint == null) {
            return null;
        }
        Map<String, String> components = new LinkedHashMap<>();
        String status;
        try {
            status = endpoint.health().getStatus().getCode();
            for (String key : HEALTH_COMPONENTS) {
                try {
                    var detail = endpoint.healthForPath(key);
                    if (detail != null) {
                        components.put(key, detail.getStatus().getCode());
                    }
                } catch (Exception e) {
                    // 该组件不存在（对应模块没装配）——缺席本身就是答案，不写「DOWN」
                    log.debug("健康组件 {} 不可用: {}", key, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("读取 actuator 健康端点失败: {}", e.getMessage());
            return null;
        }
        return MonitorOverviewVO.Health.builder().status(status).components(components).build();
    }

    /* ==================== 读数小工具 ==================== */

    /** 按名字（可再带一组 tag）读 gauge 现值；多个同名 gauge（如多池）求和。取不到返回 null */
    private Double gaugeValue(String name, Map<String, String> tags) {
        var search = registry.find(name);
        if (tags != null) {
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                search = search.tag(tag.getKey(), tag.getValue());
            }
        }
        Collection<Gauge> gauges = search.gauges();
        if (gauges.isEmpty()) {
            return null;
        }
        double total = 0;
        for (Gauge gauge : gauges) {
            total += gauge.value();
        }
        return total;
    }

    private Long toMb(Double bytes) {
        return bytes == null ? null : (long) (bytes / 1024 / 1024);
    }

    /** 分位数已是毫秒；0 视为「该尺子没量过」而不是「0 毫秒」 */
    private Double toMs(double millis) {
        return millis <= 0 ? null : round2(millis);
    }

    private Double round1(double v) {
        return Math.round(v * 10) / 10D;
    }

    private Double round2(double v) {
        return Math.round(v * 100) / 100D;
    }
}
