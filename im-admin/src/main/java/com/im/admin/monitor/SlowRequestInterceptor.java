package com.im.admin.monitor;

import com.im.admin.config.AdminProperties;
import com.im.common.util.SecurityUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 慢接口拦截器：量的是「整个 HTTP 请求」的端到端耗时，超过阈值就留证据。
 *
 * <p>三条通道各管一件事，都指向同一个事实（这条请求慢了），但受众不同：
 * <ol>
 *   <li><b>WARN 日志</b>走 logger {@code com.im.slowapi}，由 logback 落
 *       {@code SLOW-API/slow-api.log}——进程重启、缓冲清空后，这里是唯一的历史；</li>
 *   <li><b>环形缓冲</b>只留最近 N 条，给监控页即时回看（谁在什么时候被哪个接口卡住）；</li>
 *   <li><b>Prometheus counter</b>（{@code im_slow_request_total}）给告警规则用——
 *       「5 分钟内慢接口占比超阈值」这种判断该由 Alertmanager 做，不该由内存里的缓冲做。</li>
 * </ol>
 *
 * <p>为什么不干脆只用 actuator 自带的 {@code http.server.requests} timer：它是按
 * uri 模板聚合的分布指标，回答「哪类接口慢」，但回答不了「刚才那一下是谁的哪条请求」；
 * 定位个案需要逐条记录，两者互补而不是重复建设。
 *
 * <p>耗时口径用 nanoTime（单调时钟，不受系统时间调整影响）；阈值 {@code <=0}
 * 时整个拦截逻辑短路，只保留计时的最小开销——属性支持热改虽然要重启才生效，
 * 但「先关掉止血」这个动作不该被代码结构绑住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowRequestInterceptor implements HandlerInterceptor {

    /** request attribute：进 preHandle 的时刻（nanoTime） */
    private static final String ATTR_START_NANOS = "im.request.startNanos";

    /** 慢接口告警通道，logback 里 additivity=false，只落 SLOW-API 文件与控制台 */
    private static final org.slf4j.Logger SLOW_API_LOG = org.slf4j.LoggerFactory.getLogger("com.im.slowapi");

    private final AdminProperties adminProperties;
    private final MeterRegistry meterRegistry;

    /** 头进尾出：新记录放头部，超容量从尾部淘汰，读侧永远拿到「最近优先」的顺序 */
    private final ConcurrentLinkedDeque<SlowRequestRecord> history = new ConcurrentLinkedDeque<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        long threshold = adminProperties.getSlowRequestMillis();
        if (threshold <= 0) {
            return;
        }
        Object start = request.getAttribute(ATTR_START_NANOS);
        if (!(start instanceof Long startNanos)) {
            // preHandle 没执行（被更早的拦截器挡下），没有计时起点就没有可说的
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (durationMs < threshold) {
            return;
        }
        Long userId = null;
        try {
            // 匿名接口被挡时取不到用户，慢照样要记，userId 留空即可
            userId = SecurityUtil.getUserIdOrNull();
        } catch (Exception ignore) {
            // 上下文已回收等极端情况不影响记账
        }
        SlowRequestRecord record = new SlowRequestRecord(LocalDateTime.now(),
                request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, userId);
        history.addFirst(record);
        while (history.size() > Math.max(1, adminProperties.getSlowRequestHistory())) {
            history.pollLast();
        }
        meterRegistry.counter("im.slow.request.total").increment();
        SLOW_API_LOG.warn("慢接口 {} {} 耗时 {}ms（阈值 {}ms），status={}, userId={}",
                record.method(), record.path(), durationMs, threshold, record.status(), userId);
    }

    /** 监控页取「最近慢接口」：返回不可变拷贝，容量口径由属性控制 */
    public List<SlowRequestRecord> recent() {
        return List.copyOf(history);
    }

    /** 累计慢接口条数（进程生命周期内），取 counter 现值而不是数 deque（deque 是滑动窗口） */
    public double slowTotal() {
        Counter counter = meterRegistry.find("im.slow.request.total").counter();
        return counter == null ? 0D : counter.count();
    }
}
