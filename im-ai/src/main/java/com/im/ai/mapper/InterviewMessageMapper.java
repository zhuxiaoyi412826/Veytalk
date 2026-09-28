package com.im.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.ai.entity.InterviewMessage;

import java.util.List;

/**
 * 面试问答 Mapper。
 */
public interface InterviewMessageMapper extends BaseMapper<InterviewMessage> {

    /**
     * 取会话内已有的最大序号，没有记录时返回 0。
     *
     * <p>序号由服务端算而不是前端带：多标签页同时开面试、前端重试都会让自报的序号撞车。
     * seq 只负责排序，不建唯一约束（并发下两个请求可能算出同一个 seq，
     * 约束拦下就会把其中一行当成「已存在」丢掉）；幂等靠 {@code uk_session_turn}。
     */
    default int maxSeq(Long sessionId) {
        List<Object> seqs = selectObjs(Wrappers.<InterviewMessage>query()
                .select("MAX(seq)")
                .eq("session_id", sessionId));
        if (seqs.isEmpty() || !(seqs.get(0) instanceof Number number)) {
            return 0;
        }
        return number.intValue();
    }

    /** 会话内的问答行数，用于估算轮数与校对 turn_count */
    default long countBySession(Long sessionId) {
        return selectCount(Wrappers.<InterviewMessage>query().eq("session_id", sessionId));
    }
}
