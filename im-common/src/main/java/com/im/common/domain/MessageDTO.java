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
 * 消息传输对象，跨模块与 WebSocket 推送共用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "消息")
public class MessageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "服务端消息 ID（雪花）")
    private Long messageId;

    @Schema(description = "客户端消息 ID，用于幂等与本地消息对齐")
    private String clientMsgId;

    @Schema(description = "所属会话 ID")
    private Long conversationId;

    @Schema(description = "会话类型：1 单聊 2 群聊")
    private Integer conversationType;

    @Schema(description = "发送者 ID，0 表示系统")
    private Long fromUserId;

    @Schema(description = "发送者昵称")
    private String fromNickname;

    @Schema(description = "发送者头像")
    private String fromAvatar;

    @Schema(description = "消息类型：1 文本 2 图片 3 文件 4 语音 5 系统通知")
    private Integer msgType;

    @Schema(description = "消息内容：文本为正文，附件类为文件 ID")
    private String content;

    @Schema(description = "扩展信息：附件元数据、@ 列表等")
    private MessageExtra extra;

    @Schema(description = "会话内自增序列，用于游标分页与未读计算")
    private Long seq;

    @Schema(description = "消息状态：1 已发送 2 已送达 3 已读 4 已撤回")
    private Integer status;

    @Schema(description = "是否已撤回")
    private Boolean recalled;

    @Schema(description = "发送时间")
    private LocalDateTime sendTime;
}
