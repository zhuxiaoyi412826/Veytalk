package com.im.admin.dto.vo;

import com.im.admin.monitor.SlowRequestRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 监控告警页的总览响应（三期）。
 *
 * <p>所有分组与数值字段都是包装类型可空：<b>取不到的指标显示「—」而不是 0</b>——
 * 与数据看板同一口径（{@code AdminMetricsSpi} 的 value=null 约定）。把「指标没注册」
 * 画成 0，等于在监控页上制造一个永远不会告警的假健康值。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "监控总览")
public class MonitorOverviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本次取数时刻")
    private LocalDateTime collectedAt;

    @Schema(description = "进程已运行秒数")
    private Long uptimeSeconds;

    @Schema(description = "JVM 内存与线程")
    private Jvm jvm;

    @Schema(description = "HTTP 请求分布（actuator http.server.requests）")
    private Http http;

    @Schema(description = "慢接口拦截器视角")
    private Slow slow;

    @Schema(description = "HikariCP 连接池")
    private Pool pool;

    @Schema(description = "actuator 健康检查聚合结果")
    private Health health;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "JVM 指标")
    public static class Jvm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long heapUsedMb;
        private Long heapMaxMb;
        private Double heapPct;
        private Long nonHeapUsedMb;
        private Integer threadCount;
        private Integer cpuCores;
        /** 系统 1 分钟平均负载（-1 表示该 JDK 不支持采集，前端显示「—」） */
        private Double systemLoadAverage;
        /** 本进程 CPU 占用比（0~1），采不到为 null */
        private Double processCpuPct;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "HTTP 指标")
    public static class Http implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 进程启动以来累计请求数 */
        private Long requestCount;
        /** 其中 5xx（outcome=SERVER_ERROR）条数 */
        private Long serverErrorCount;
        private Double avgMs;
        private Double p50Ms;
        private Double p95Ms;
        private Double p99Ms;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "慢接口")
    public static class Slow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long thresholdMs;
        /** 进程生命周期内累计触发的慢接口条数（Prometheus 同名 counter 的现值） */
        private Double slowTotal;
        /** 最近 N 条明细，时间倒序 */
        private List<SlowRequestRecord> recent;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "连接池")
    public static class Pool implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Integer active;
        private Integer idle;
        private Integer max;
        /** 等待连接的线程数：&gt;0 持续出现说明池子小了或有慢 SQL 占着连接 */
        private Integer pending;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "健康检查")
    public static class Health implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 聚合状态：UP / DOWN / OUT_OF_SERVICE / UNKNOWN */
        private String status;
        /** 逐个关键组件（db / redis / ping / diskSpace）的状态，缺席的不在表里 */
        private Map<String, String> components;
    }
}
