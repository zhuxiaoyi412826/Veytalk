package com.im.admin.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 后台公告列表的筛选入参。
 *
 * <p>{@code status} 是唯一一个走索引的条件（{@code idx_status_publish} 的前导列），
 * 关键词那一路是 {@code title/content} 双 LIKE，走不了索引——公告表是「一年几十条」量级的表，
 * 全表扫的成本比给一段中文文案建全文索引小得多，所以这一路不必收敛成精确匹配。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "后台公告列表筛选入参")
public class AnnouncementQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "状态过滤：0 草稿 1 已发布 2 已撤回；为空查全部", example = "1")
    private Integer status;
}
