package com.im.friend.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.time.LocalDate;

/**
 * 「我的动态」分页查询。
 *
 * <p>与 Feed 的游标翻页不是一回事：Feed 是一条不断往前插的流，页码会在翻页之间把旧内容挤到
 * 下一屏去；而这一屏是<b>作者翻自己的历史</b>，要的是「第 3 页永远是那 20 条」，
 * 还要能按时间区间、内容形态和热度下限筛，所以用页码。
 *
 * <p>{@link #sortBy} 只接受一个固定字典，服务层把它映射成实体上的方法引用再交给 Mapper——
 * 绝不把列名字符串一路传到 SQL 里，那等于开一个 ORDER BY 注入口。
 *
 * <p>{@link #startDate} / {@link #endDate} 收日期而不收时间戳：前端的区间控件给的就是「哪一天到
 * 哪一天」，而「结束日期」在服务层展开成<b>次日零点做开区间上界</b>，这样 23:59:59 发的动态
 * 也算在当天里，不会因为秒被截掉而凭空少一条。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的动态分页查询")
public class MomentMineQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "内容形态：不传查全部，1 图文 2 视频 3 纯文字", example = "2")
    private Integer contentType;

    @Schema(description = "状态：不传查全部（含已下架），1 正常 2 已下架", example = "1")
    private Integer status;

    @Schema(description = "发布时间起（含），格式 yyyy-MM-dd", example = "2026-09-01")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @Schema(description = "发布时间止（含当天，服务端按次日零点做开区间上界），格式 yyyy-MM-dd",
            example = "2026-09-16")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    @Schema(description = "排序字段：time 发布时间（默认）/ comment 评论数 / view 浏览量 / like 点赞数",
            example = "view")
    private String sortBy;

    @Schema(description = "排序方向：desc 倒序（默认）/ asc 升序", example = "desc")
    private String sortOrder;

    @Schema(description = "评论数下限（含），只看达到这个互动量的动态", example = "5")
    private Integer minCommentCount;

    @Schema(description = "浏览量下限（含）", example = "50")
    private Integer minViewCount;

    @Schema(description = "点赞数下限（含）", example = "10")
    private Integer minLikeCount;
}
