package com.im.message.service;

/**
 * 会话内消息序列号发号器。
 *
 * <p>{@code seq} 是会话内单调递增的序号，承担三个职责：历史消息游标分页、
 * 离线消息位点比较、消息排序。因此它必须满足「同一会话内唯一且递增」，
 * 但不同会话之间互相独立，都从 1 开始。
 */
public interface MessageSequenceService {

    /**
     * 取下一个序列号。
     *
     * @param conversationId 会话 ID
     * @return 严格大于该会话已有最大 seq 的整数
     */
    long nextSeq(Long conversationId);
}
