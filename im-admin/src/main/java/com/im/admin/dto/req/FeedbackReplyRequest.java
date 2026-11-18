package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台回复意见反馈的入参。
 *
 * <p>再次回复直接覆盖旧文（表设计如此，见 {@code Feedback} 注释）：反馈是客服台账，
 * 不做回复版本化。回复人取登录态，不在入参里。
 */
@Data
@Schema(description = "意见反馈回复入参")
public class FeedbackReplyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "回复内容，最多 1000 字；写入即把该条反馈置为已回复",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "已复现，将在下个版本修复")
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 1000, message = "回复内容最多 1000 字")
    private String reply;
}
