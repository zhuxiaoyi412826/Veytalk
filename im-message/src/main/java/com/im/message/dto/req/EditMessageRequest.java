package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 编辑消息入参。
 *
 * <p>只承载新正文，是否文本、是否本人、是否在时限内、长度与敏感词都由 Service 统一裁决；
 * 这里的 {@code @Size} 只是最外层的一刀粗筛，与服务端 {@code im.message.max-text-length} 口径一致。
 */
@Data
@Schema(description = "编辑消息入参")
public class EditMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "编辑后的正文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 5000, message = "消息内容过长")
    private String content;
}
