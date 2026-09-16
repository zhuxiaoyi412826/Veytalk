package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 引用/回复消息的预览快照。
 *
 * <p>前端渲染引用块时只需要「谁说的、说了什么、是否已撤回」这几项，
 * 把原消息的完整 VO 嵌套进来会让报文体积翻倍且暴露不必要的字段（seq、status、readCount 等）。
 *
 * <p>内容取的是摘要而非原文：附件类消息的 content 是文件 ID，直接显示会露出一串雪花数字；
 * 已撤回的消息只给一个标记，前端据此渲染「引用内容已撤回」占位。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "引用消息预览")
public class QuotePreview implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "被引用消息的服务端 ID")
    private Long messageId;

    @Schema(description = "被引用消息的发送者 ID")
    private Long fromUserId;

    @Schema(description = "被引用消息的发送者昵称")
    private String fromNickname;

    @Schema(description = "被引用消息的类型：1 文本 2 图片 3 文件 4 语音 5 系统通知")
    private Integer msgType;

    @Schema(description = "摘要文本，附件类为「[图片]」等占位，已撤回时为 null")
    private String content;

    @Schema(description = "被引用的原消息是否已撤回")
    private Boolean recalled;
}
