package com.im.message.dto.req;

import com.im.common.domain.MessageExtra;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 发送消息入参。
 *
 * <p>会话定位支持三种方式，按优先级取第一个非空的：{@code conversationId} &gt; {@code toUserId} &gt; {@code toGroupId}。
 * 前端在聊天窗口里已经持有 conversationId，好友列表点「发起聊天」时只有 toUserId，
 * 两种入口共用一个接口可以避免前端维护两套发送逻辑。
 */
@Data
@Schema(description = "发送消息入参")
public class SendMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "客户端消息 ID，幂等键；建议用 UUID，同一条消息重发时必须保持不变",
            example = "9f1c2b7e-4d5a-4b6c-8e9f-0a1b2c3d4e5f", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "clientMsgId 不能为空")
    @Size(max = 64, message = "clientMsgId 长度不能超过 64")
    private String clientMsgId;

    @Schema(description = "会话 ID，与 toUserId / toGroupId 三选一")
    private Long conversationId;

    @Schema(description = "单聊接收者 ID，服务端会自动获取或创建会话")
    private Long toUserId;

    @Schema(description = "群聊接收群 ID，服务端会自动获取或创建群会话")
    private Long toGroupId;

    @Schema(description = "消息类型：1 文本 2 图片 3 文件 4 语音", example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "消息类型不能为空")
    private Integer msgType;

    @Schema(description = "消息内容：文本消息为正文，图片/文件/语音为文件 ID")
    private String content;

    @Schema(description = "扩展信息，附件类消息可携带宽高、时长等；服务端会按文件元数据回填覆盖")
    private MessageExtra extra;

    @Schema(description = "被 @ 的用户 ID 列表，仅群聊有效")
    private List<Long> atUserIds;

    @Schema(description = "是否 @ 全员，仅群聊有效")
    private Boolean atAll;
}
