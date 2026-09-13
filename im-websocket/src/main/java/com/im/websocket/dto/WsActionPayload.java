package com.im.websocket.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 上行回执与操作类报文的业务载荷，供 {@code delivered} / {@code read} / {@code recall} 三种类型共用。
 *
 * <p>三种报文各自只用其中一部分字段：
 * <ul>
 *   <li>{@code delivered} —— {@link #messageIds}</li>
 *   <li>{@code read} —— {@link #conversationId} + {@link #maxSeq}（maxSeq 为空表示整个会话都已读）</li>
 *   <li>{@code recall} —— {@link #messageId}</li>
 * </ul>
 *
 * <p>合成一个类而不是拆三个，是因为它们都只有两三个字段、且都由 {@code WsInboundDispatcher}
 * 在同一个 switch 里处理；拆开会让包内多出一堆只被用一次的小类，
 * 而合并后「哪个类型该填哪些字段」这件事集中在一处 javadoc 里，反而更容易核对。
 * 校验放在 dispatcher 里按类型分别做，不依赖这里声明约束。
 */
@Data
public class WsActionPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待标记送达的消息 ID 列表 */
    private List<Long> messageIds;

    /** 会话 ID，已读上报用 */
    private Long conversationId;

    /** 已读到的最大会话内序列号，为空表示整个会话全部已读 */
    private Long maxSeq;

    /** 待撤回的消息 ID */
    private Long messageId;
}
