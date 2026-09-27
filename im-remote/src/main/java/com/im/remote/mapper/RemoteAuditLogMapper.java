package com.im.remote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.remote.entity.RemoteAuditLog;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface RemoteAuditLogMapper extends BaseMapper<RemoteAuditLog> {

    /**
     * 批量统计多个会话的审计条数，供会话历史列表显示「这个会话有多少条流水」。
     *
     * <p>一次 GROUP BY 而不是逐行 COUNT：会话历史一页最多 50 行，逐行查就是 50 次往返，
     * 而列表本身只需要一个数字用来提示「点进去有没有东西」。
     *
     * @return key 为 sessionId，没有审计记录的会话不会出现在结果里（调用方按 0 处理）
     */
    default Map<Long, Long> countBySessionIds(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = selectMaps(Wrappers.<RemoteAuditLog>query()
                .select("session_id", "COUNT(*) AS cnt")
                .in("session_id", sessionIds)
                .groupBy("session_id"));
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object sessionId = row.get("session_id");
            Object count = row.get("cnt");
            if (sessionId instanceof Number id && count instanceof Number cnt) {
                result.put(id.longValue(), cnt.longValue());
            }
        }
        return result;
    }
}
