package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单聊转账请求。
 *
 * <p>只带 {@code conversationId} 而不带 {@code toUserId}：收款人由服务端从会话反查（
 * {@code ConversationSpi#getTargetId}），客户端传的目标随时可以与会话对不上，
 * 而「转给会话里的那个人」才是用户在聊天窗口里真实表达的意图。
 *
 * <p>金额上下界不写死在注解里（{@code im.wallet.transfer-min/max} 可配），注解只兜住「正数」这条硬底线，
 * 与 {@link RechargeRequest} 同一分工。
 */
@Data
@Schema(description = "单聊转账请求")
public class TransferRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "单聊会话 ID，收款人由该会话反查", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;

    @Schema(description = "转账积分数", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "转账数额不能为空")
    @Min(value = 1, message = "转账数额必须为正整数")
    private Long amount;

    @Schema(description = "附言，可空；服务端 XSS 清洗 + 敏感词遮蔽后截断")
    @Size(max = 60, message = "附言不能超过 60 个字符")
    private String memo;
}
