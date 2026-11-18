package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 意见反馈（后台视图）。
 *
 * <p>{@code userName} 由 {@code AdminUserSpi.listByIds} 批量解析（不冗余进表），
 * 账号已注销时为 {@code null}——前端显示「已注销」而不是空串，
 * 空串会被读成「渲染坏了」。
 */
@Data
@Builder
@Schema(description = "意见反馈（后台视图）")
public class FeedbackVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "反馈 ID")
    private Long id;

    @Schema(description = "提交人用户 ID")
    private Long userId;

    @Schema(description = "提交人昵称；账号已注销时为 null")
    private String userName;

    @Schema(description = "反馈类型码")
    private String type;

    @Schema(description = "反馈类型中文名")
    private String typeName;

    @Schema(description = "反馈正文")
    private String content;

    @Schema(description = "联系方式（用户选填）")
    private String contact;

    @Schema(description = "状态：0 待处理 1 已回复")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusName;

    @Schema(description = "后台回复原文；未回复为 null")
    private String reply;

    @Schema(description = "回复人（后台账号 ID）")
    private Long replyBy;

    @Schema(description = "最近一次回复时刻")
    private LocalDateTime replyTime;

    @Schema(description = "提交时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
