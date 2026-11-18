package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户提交意见反馈的入参（前台设置页）。
 *
 * <p>userId 不在入参里：提交人一律取登录态（{@code SecurityUtil.getUserId()}），
 * 让调用方传 userId 等于允许替别人提反馈。
 */
@Data
@Schema(description = "意见反馈提交入参")
public class FeedbackSubmitRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "反馈类型码，取值见 FeedbackType：bug / suggestion / experience / other",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "bug")
    @NotBlank(message = "请选择反馈类型")
    private String type;

    @Schema(description = "反馈正文，最多 1000 字", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "群聊里发图片偶尔转圈很久才发出去")
    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 1000, message = "反馈内容最多 1000 字")
    private String content;

    @Schema(description = "联系方式（选填），最多 64 字：愿意被回访时才填", example = "13800000000")
    @Size(max = 64, message = "联系方式最多 64 个字符")
    private String contact;
}
