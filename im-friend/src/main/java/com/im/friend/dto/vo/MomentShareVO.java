package com.im.friend.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「动态转聊天」的结果。
 *
 * <p>{@code conversationId} 是这一页最有用的一项：转发完之后前端要跳进那个会话，
 * 而单聊会话可能是这次才建出来的，前端手上原本没有它的 ID。
 *
 * <p>不回填整条消息：气泡的内容由消息模块按 {@code MessageExtra} 自己渲染，
 * 在这里再抄一份 {@code MessageDTO} 就会出现两套字段名，而两套字段名总会有一天对不上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "动态转聊天结果")
public class MomentShareVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "目标会话 ID（单聊会话可能是这次才建出来的）")
    private Long conversationId;

    @Schema(description = "落库后的消息 ID")
    private Long messageId;
}
