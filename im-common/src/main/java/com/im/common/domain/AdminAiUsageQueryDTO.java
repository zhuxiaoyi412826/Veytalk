package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 调用明细的查询条件（im-admin 组装、im-ai 执行）。
 *
 * <p>排序固定在实现方：{@code (create_time, id)} 倒序，最新一笔在最前。
 * 不提供时间范围过滤：这一屏的量级（一人一天几十行）翻页足够用，
 * 加两个 DATETIME 条件反而诱导管理员拿它做报表——报表该看聚合那一屏。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAiUsageQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个用户（从聚合页点进来时带） */
    private Long userId;

    /** 场景精确过滤，空白不过滤 */
    private String scene;

    /** 只看成功（true）或只看失败（false），null 不过滤 */
    private Boolean success;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限 */
    private long size;
}
