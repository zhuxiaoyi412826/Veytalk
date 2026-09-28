package com.im.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.ai.entity.InterviewEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试事件 Mapper。
 */
public interface InterviewEventMapper extends BaseMapper<InterviewEvent> {

    /**
     * 按事件类型统计一个会话的违规条数，给审计详情页做「切屏 3 次 / 粘贴 2 次」的分解展示。
     *
     * <p>会话表上的计数是汇总值，看不出构成；这一句 GROUP BY 只在点开单场面试时才跑，
     * 列表页不查，所以不必为它加冗余列。
     *
     * @return key 为 eventType，value 为条数；只含 violation=1 的事件
     */
    default Map<String, Long> countViolationByType(Long sessionId) {
        List<Map<String, Object>> rows = selectMaps(Wrappers.<InterviewEvent>query()
                .select("event_type", "COUNT(*) AS cnt")
                .eq("session_id", sessionId)
                .eq("violation", 1)
                .groupBy("event_type"));
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("event_type") instanceof String type && row.get("cnt") instanceof Number cnt) {
                result.put(type, cnt.longValue());
            }
        }
        return result;
    }
}
