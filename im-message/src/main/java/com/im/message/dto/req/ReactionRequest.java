package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 表情回应入参。
 *
 * <p>{@code remove} 决定是取消还是添加：{@code false} 贴一个表情（已存在则幂等），
 * {@code true} 撤掉本人这条回应。是否合法、是否成员、是否已撤回都由 Service 统一裁决。
 */
@Data
@Schema(description = "表情回应入参")
public class ReactionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "表情字符，如 👍 ❤️ 😄", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "表情不能为空")
    @Size(max = 32, message = "表情过长")
    private String emoji;

    @Schema(description = "true 取消该表情回应，false 添加", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "remove 不能为空")
    private Boolean remove;
}
