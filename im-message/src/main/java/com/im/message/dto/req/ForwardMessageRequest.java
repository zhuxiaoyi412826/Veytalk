package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 转发消息入参。
 *
 * <p>目标会话支持三种定位方式，与发送接口一致：{@code conversationId} &gt; {@code toUserId} &gt; {@code toGroupId}。
 * 转发不携带引用（quoteMsgId 为空），收到的消息就是一条普通的新消息，
 * 只是内容来自另一条已存在的消息。
 */
@Data
@Schema(description = "转发消息入参")
public class ForwardMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "被转发的原消息 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "messageId 不能为空")
    private Long messageId;

    @Schema(description = "目标会话 ID，与 toUserId / toGroupId 三选一")
    private Long conversationId;

    @Schema(description = "单聊接收者 ID，服务端会自动获取或创建会话")
    private Long toUserId;

    @Schema(description = "群聊接收群 ID，服务端会自动获取或创建群会话")
    private Long toGroupId;

    @Schema(description = "客户端消息 ID，幂等键；不传时由服务端生成")
    private String clientMsgId;
}
