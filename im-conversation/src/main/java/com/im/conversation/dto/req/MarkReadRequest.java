package com.im.conversation.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标记会话已读入参。请求体可整体省略，表示整个会话全部已读。
 */
@Data
@Schema(description = "标记已读入参")
public class MarkReadRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "已读到的消息 seq，留空表示整个会话全部已读", example = "42")
    @PositiveOrZero(message = "seq 不能为负数")
    private Long lastAckSeq;
}
