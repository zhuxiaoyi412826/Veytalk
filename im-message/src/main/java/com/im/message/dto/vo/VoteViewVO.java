package com.im.message.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 投票视图：把 {@code extra} 里的静态定义与 {@code im_message_vote} 表实时聚合的票数合成一份，
 * 供气泡里的投票卡片直接渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "投票视图")
public class VoteViewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "投票标题")
    private String title;

    @Schema(description = "是否多选")
    private Boolean multiple;

    @Schema(description = "是否匿名")
    private Boolean anonymous;

    @Schema(description = "是否已截止")
    private Boolean closed;

    @Schema(description = "总投票人数")
    private Integer totalVoters;

    @Schema(description = "各选项聚合结果，顺序与定义一致")
    private List<VoteOptionVO> options;
}
