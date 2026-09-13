package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 消息发送指令，跨模块调用 {@code MessageSpi#send} 时使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "消息发送指令")
public class MessageSendCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "客户端消息 ID，用于幂等；系统消息可留空由服务端生成")
    private String clientMsgId;

    @Schema(description = "会话 ID；为空时按 fromUserId + toUserId 自动获取或创建单聊会话")
    private Long conversationId;

    @Schema(description = "发送者 ID，系统通知填 0")
    private Long fromUserId;

    @Schema(description = "单聊接收者 ID")
    private Long toUserId;

    @Schema(description = "群聊接收群 ID")
    private Long toGroupId;

    @Schema(description = "消息类型：1 文本 2 图片 3 文件 4 语音 5 系统通知")
    private Integer msgType;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "扩展信息（附件元数据等），会以 JSON 存入 im_message.extra")
    private MessageExtra extra;

    @Schema(description = "被 @ 的用户 ID 列表，仅群聊有效")
    private List<Long> atUserIds;

    @Schema(description = "是否 @ 全员，仅群聊有效")
    private Boolean atAll;

    @Schema(description = "是否跳过好友关系 / 禁言校验，仅服务端内部系统通知使用")
    @Builder.Default
    private Boolean internal = Boolean.FALSE;

    @Schema(description = "是否推送 WebSocket，默认推送")
    @Builder.Default
    private Boolean push = Boolean.TRUE;
}
