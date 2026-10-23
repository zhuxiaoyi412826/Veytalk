package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 接龙入参。
 *
 * <p>语义由 Service 统一裁决：
 * <ul>
 *   <li>{@code close=true}：发起人截止该接龙，{@code text} 被忽略；</li>
 *   <li>否则为参与：{@code text} 是本人这一行的正文，为空时回落为昵称，服务端补序号与时刻后 append 到末尾。</li>
 * </ul>
 */
@Data
@Schema(description = "接龙入参")
public class ChainRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本次接龙的条目正文，可为空（回落为本人昵称）；截止时忽略")
    private String text;

    @Schema(description = "true 表示截止接龙（仅发起人可操作）")
    private Boolean close;
}
