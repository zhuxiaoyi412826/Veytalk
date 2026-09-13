package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 已读上报入参。
 *
 * <p>与会话模块的「标记会话已读」是两个动作：那边清未读红点，这边写 {@code im_message_read}
 * 回执并把「已读」推送给发送方，气泡上的双勾靠它才会亮。
 */
@Data
@Schema(description = "已读上报入参")
public class ReadReportRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;

    @Schema(description = "已读到的消息位点 seq，为空表示会话内全部消息已读")
    private Long maxSeq;
}
