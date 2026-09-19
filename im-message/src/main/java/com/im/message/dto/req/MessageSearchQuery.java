package com.im.message.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 消息检索入参。
 *
 * <p>{@code conversationId} 可选：指定时在该会话内检索；为空时跨「当前用户参与的全部会话」
 * 全局检索。两种情形都不会退化成全表扫描——单会话走 {@code idx_conv_seq} 前缀，
 * 全局则把 {@code LIKE} 的扫描范围收敛到 {@code ConversationSpi.listByUser} 返回的会话集合内。
 * 关键字沿用父类的 {@code keyword} 字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "消息检索入参")
public class MessageSearchQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID，为空表示跨全部会话全局检索")
    private Long conversationId;
}
