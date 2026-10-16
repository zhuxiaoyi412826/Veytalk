package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 投票入参。
 *
 * <p>语义由 Service 统一裁决：
 * <ul>
 *   <li>{@code close=true}：发起人截止该投票，{@code options} 被忽略；</li>
 *   <li>否则为投票：{@code options} 是本次选定的选项下标集合（整体替换旧票）；空集合表示撤回本人本次投票。
 *   单选至多一个，多选不限，下标越界会被拒。</li>
 * </ul>
 */
@Data
@Schema(description = "投票入参")
public class VoteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本次选定的选项下标集合（从 0 开始），空表示撤回投票；截止时忽略")
    private List<Integer> options;

    @Schema(description = "true 表示截止投票（仅发起人可操作）")
    private Boolean close;
}
