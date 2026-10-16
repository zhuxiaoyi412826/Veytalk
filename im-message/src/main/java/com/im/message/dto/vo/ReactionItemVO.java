package com.im.message.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 表情回应聚合项：一条消息下某个表情的汇总，供气泡下的表情栏直接渲染。
 *
 * <p>一个表情一项，{@code count} 是贴该表情的人数，{@code self} 表示当前查看者自己是否也贴了
 * （决定这颗表情是否高亮）。由服务端按 {@code im_message_reaction} 行内存聚合而来。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "表情回应聚合项")
public class ReactionItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "表情字符")
    private String emoji;

    @Schema(description = "贴该表情的人数")
    private Integer count;

    @Schema(description = "当前查看者是否也贴了该表情")
    private Boolean self;
}
