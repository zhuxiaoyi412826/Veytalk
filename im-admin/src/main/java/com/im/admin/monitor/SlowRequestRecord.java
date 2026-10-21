package com.im.admin.monitor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条被慢接口拦截器记下的请求快照（给监控页的「最近慢接口」列表用）。
 *
 * <p>只存定位问题需要的最小信息：路径、耗时、状态码、发起人。
 * <b>不存请求体与查询串里的参数值</b>——慢的可能恰好是带手机号搜索的那一条，
 * 环形缓冲是内存里的长期驻留物，不该变成另一份需要清理的敏感数据副本。
 *
 * <p>record 是不可变的，写读两侧都只碰 {@code SlowRequestInterceptor} 里的 deque，
 * 对外给出的列表是拷贝，监控页拿到的快照不会因为新请求进来而当场变形。
 */
public record SlowRequestRecord(
        LocalDateTime time,
        String method,
        String path,
        int status,
        long durationMs,
        Long userId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
