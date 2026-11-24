package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 建议里的一条文案。
 *
 * <p>{@link #content} 是<b>已经替换过占位符的成品</b>，前端拿到就能直接复制去发，
 * 不需要也不应该再做一次模板替换（替换规则见 {@code Icebreaker#render}）。
 *
 * <p>不给 type 字段：它属于哪一档已经由外层 {@link IcebreakerSuggestVO} 的三个数组说明，
 * 再放一个 code 就等于让前端在两个出处之间自己判一致性。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "一条破冰文案")
public class IcebreakerItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "文案 ID（im_icebreaker.id），点「用这条」时回传它计数")
    private Long id;

    @Schema(description = "成品文案，占位符已替换")
    private String content;

    @Schema(description = "这条文案绑定的共同标签名；通用文案为 null")
    private String tagName;
}
