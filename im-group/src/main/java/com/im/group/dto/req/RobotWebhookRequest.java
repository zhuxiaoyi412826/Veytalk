package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 群机器人 Webhook 回调入参（外部系统调用，无需登录态，凭 URL 里的 token 认证）。
 */
@Data
@Schema(description = "群机器人 Webhook 回调入参")
public class RobotWebhookRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "要发送到群里的文本内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "内容不能为空")
    @Size(max = 2000, message = "内容长度不能超过 2000")
    private String content;
}
