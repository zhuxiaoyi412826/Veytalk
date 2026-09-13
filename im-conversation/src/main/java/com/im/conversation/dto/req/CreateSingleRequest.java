package com.im.conversation.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建单聊会话入参。
 */
@Data
@Schema(description = "创建单聊会话入参")
public class CreateSingleRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "对方用户 ID", example = "1002", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "目标用户不能为空")
    private Long targetUserId;
}
