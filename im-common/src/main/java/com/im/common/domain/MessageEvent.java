package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 新消息落库后广播给会话模块的事件，用于更新会话摘要与未读计数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "新消息事件")
public class MessageEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID")
    private Long conversationId;

    @Schema(description = "会话类型：1 单聊 2 群聊")
    private Integer conversationType;

    @Schema(description = "消息 ID")
    private Long messageId;

    @Schema(description = "消息序列号")
    private Long seq;

    @Schema(description = "发送者 ID")
    private Long fromUserId;

    @Schema(description = "消息类型")
    private Integer msgType;

    @Schema(description = "用于会话列表展示的内容摘要")
    private String summary;

    @Schema(description = "发送时间")
    private LocalDateTime sendTime;

    @Schema(description = "需要增加未读数的接收者 ID 列表（不含发送者）")
    private List<Long> receiverIds;

    @Schema(description = "被 @ 的用户 ID 列表")
    private List<Long> atUserIds;

    @Schema(description = "是否 @ 全员")
    private Boolean atAll;
}
