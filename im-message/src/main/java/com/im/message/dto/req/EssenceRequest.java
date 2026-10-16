package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 精华消息标记入参。
 *
 * <p>{@code essence=true} 把该消息设为群精华，{@code false} 取消精华；仅群聊会话、群主与管理员可操作。
 */
@Data
@Schema(description = "精华消息标记入参")
public class EssenceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "true 设为精华，false 取消精华")
    private Boolean essence;
}
