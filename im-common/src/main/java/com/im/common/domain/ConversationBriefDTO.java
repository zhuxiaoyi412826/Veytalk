package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话摘要，跨模块传输使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话摘要")
public class ConversationBriefDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID")
    private Long conversationId;

    @Schema(description = "会话类型：1 单聊 2 群聊")
    private Integer type;

    @Schema(description = "会话目标 ID：单聊为对方用户 ID，群聊为群 ID")
    private Long targetId;

    @Schema(description = "展示名称：对方昵称或群名")
    private String name;

    @Schema(description = "展示头像")
    private String avatar;

    @Schema(description = "未读消息数")
    private Integer unreadCount;

    @Schema(description = "最后一条消息摘要")
    private String lastMsgContent;

    @Schema(description = "最后一条消息类型")
    private Integer lastMsgType;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastMsgTime;

    @Schema(description = "是否置顶")
    private Boolean top;

    @Schema(description = "是否消息免打扰")
    private Boolean muted;

    @Schema(description = "对方是否在线，仅单聊有效")
    private Boolean online;

    @Schema(description = "我对对方的好友备注")
    private String remark;

    @Schema(description = "已确认接收的消息位点 seq，im-message 据此计算离线消息")
    private Long lastAckSeq;
}
