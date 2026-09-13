package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量送达上报入参。
 *
 * <p>单次上限 200 条：客户端一般按「一屏消息」上报，200 已经远超一屏容量，
 * 再大只会说明客户端在重复上报历史消息，限制住可以防止一次请求打爆批量 SQL。
 */
@Data
@Schema(description = "批量送达上报入参")
public class MessageIdsRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "消息 ID 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "消息 ID 列表不能为空")
    @Size(max = 200, message = "单次最多上报 200 条")
    private List<Long> messageIds;
}
