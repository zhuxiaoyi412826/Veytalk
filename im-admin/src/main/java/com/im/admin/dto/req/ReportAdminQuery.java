package com.im.admin.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 后台举报队列的筛选入参。
 *
 * <p>三个筛选条件都是<b>码</b>而不是中文名：队列按 {@code status / target_type / reason_code} 过滤，
 * 这三列在 {@code im_report} 上各有索引，而按 reason 的中文文案过滤需要一份「文案→码」的反查，
 * 那份反查迟早和 {@code ReportReason} 漂移。前端下拉的文案由 {@code /api/report/config} 给出（后台登录态同样能读），
 * 提交的是码；后台不另开一份字典接口，两处字典早晚不一致。
 *
 * <p>{@code keyword} 沿用父类字段，含义在 service 里解释：纯数字时当作「被举报对象 ID 或被举报人 ID」
 * 精确匹配，否则只在 {@code reason_text} 上做前缀有限的 LIKE。之所以不搜昵称，是因为昵称在 im-user，
 * 后台为了一个搜索框去跨模块拉全量用户名单，代价和一个搜索框完全不成比例。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "后台举报队列筛选入参")
public class ReportAdminQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "状态过滤：0 待处理 1 已受理 2 已驳回 3 已处置；为空查全部", example = "0")
    private Integer status;

    @Schema(description = "对象类型过滤：user / message / room / group；为空查全部", example = "room")
    private String targetType;

    @Schema(description = "理由码过滤：porn / abuse / violence / fraud / spam / infringement / minor / other")
    private String reasonCode;
}
