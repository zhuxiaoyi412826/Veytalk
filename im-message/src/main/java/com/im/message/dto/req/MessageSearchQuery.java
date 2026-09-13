package com.im.message.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 消息检索入参。
 *
 * <p>必须指定会话：消息表是全系统增长最快的表，不带会话范围的 {@code LIKE} 会退化成全表扫描。
 * 关键字沿用父类的 {@code keyword} 字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "消息检索入参")
public class MessageSearchQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;
}
