package com.im.admin.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 意见反馈列表查询（后台）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "意见反馈列表查询")
public class FeedbackQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "类型码过滤：bug / suggestion / experience / other；为空查全部", example = "bug")
    private String type;

    @Schema(description = "状态过滤：0 待处理 1 已回复；为空查全部", example = "0")
    private Integer status;
}
