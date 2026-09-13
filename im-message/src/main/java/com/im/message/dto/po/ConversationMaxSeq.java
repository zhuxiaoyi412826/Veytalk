package com.im.message.dto.po;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「会话 -&gt; 当前最大 seq」的查询投影，供清除离线标记时批量比对位点使用。
 *
 * <p>不复用实体 {@code Message}：这里只需要两列聚合结果，用实体承接会让 MyBatis
 * 走完整的自动映射，把 {@code extra} JSON 列一并解析一遍，纯属浪费。
 */
@Data
public class ConversationMaxSeq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long conversationId;

    /** 会话内消息的最大 seq，会话没有消息时不会出现在结果集中 */
    private Long maxSeq;
}
