package com.im.message.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 投票的单选项聚合项：选项文本 + 票数 + 当前查看者是否选了它。
 *
 * <p>票数与总人数对所有成员一致；{@code selfVoted} 按查看者判定。匿名投票也不隐藏票数与本人选择，
 * 只是不展示「谁投了什么」（本聚合项本就不含投票人身份）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "投票单选项聚合项")
public class VoteOptionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "选项下标，从 0 开始")
    private Integer index;

    @Schema(description = "选项文本")
    private String text;

    @Schema(description = "选择该选项的人数")
    private Integer count;

    @Schema(description = "当前查看者是否选了该选项")
    private Boolean selfVoted;
}
