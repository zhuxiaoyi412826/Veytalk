package com.im.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.ai.entity.InterviewSession;

/**
 * 面试会话 Mapper。
 *
 * <p>计数递增走数据库的 {@code x = x + n} 而不是「读出来加完写回去」：
 * 事件是分批并发上报的，读改写会让两批各算各的、后一批覆盖前一批，
 * 少记的那部分恰好是最需要准确的违规次数。
 */
public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {

    /**
     * 原子累加会话上的各项计数。传 0 的项不会拼进 SET，避免无谓的列写入。
     *
     * <p>只碰计数列，不碰 status/update_time 以外的字段，因此可以与事件插入分开提交
     * 而互不影响；计数与流水短暂不一致是可接受的（以事件表为准）。
     */
    default int incrementCounters(Long sessionId, int turnDelta, int violationDelta,
                                  int blurDelta, int copyDelta, int pasteDelta, int fullscreenExitDelta) {
        StringBuilder set = new StringBuilder();
        appendDelta(set, "turn_count", turnDelta);
        appendDelta(set, "violation_count", violationDelta);
        appendDelta(set, "blur_count", blurDelta);
        appendDelta(set, "copy_count", copyDelta);
        appendDelta(set, "paste_count", pasteDelta);
        appendDelta(set, "fullscreen_exit_count", fullscreenExitDelta);
        if (set.length() == 0) {
            return 0;
        }
        return update(null, Wrappers.<InterviewSession>update()
                .setSql(set.toString())
                .eq("id", sessionId));
    }

    private static void appendDelta(StringBuilder set, String column, int delta) {
        if (delta == 0) {
            return;
        }
        if (set.length() > 0) {
            set.append(", ");
        }
        // delta 是 int，不可能带进 SQL 结构，直接拼接比参数占位更直观
        set.append(column).append(" = ").append(column).append(" + ").append(delta);
    }
}
