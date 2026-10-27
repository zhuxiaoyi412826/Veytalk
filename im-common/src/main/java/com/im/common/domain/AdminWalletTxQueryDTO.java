package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台钱包账本流水的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p>排序固定在实现方：{@code (create_time, id)} 倒序。雪花 ID 递增，
 * 所以 {@code ORDER BY id DESC} 与倒着走时间等价，而后者能直接走主键倒扫、
 * 取满一页就停；不带任何过滤条件的全局翻页因此是便宜的。
 *
 * <p>反过来，{@code type} 与时间范围的组合<b>没有索引可用</b>：
 * {@code im_wallet_transaction} 只有 {@code idx_user_time (user_id, create_time)} 一条二级索引，
 * 只筛 {@code type=recharge} 不加 userId 时是一次全表扫。这张表是「只增」的账本，
 * 长起来只会慢，所以现在就把全局检索的单页上限夹到 50，并在后台页面上<b>不做自动轮询</b>。
 * 真要提速是补一条 {@code idx_type_time (type, create_time)} 的 DDL——本批承诺零表结构变更，
 * 那条 ALTER 连同它的写放大代价写在开发指南里，等流水真的变大再决定。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletTxQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个用户的账本；null 为全库检索（单页上限更严） */
    private Long userId;

    /** 流水类型精确过滤：recharge / gift / checkin / adjust / transfer / packet / refund / live_pay */
    private String type;

    /** 只看出账（{@code amount < 0}）；null 或 false 不限方向。进账不需要单独一个开关——
     * 挑 {@code type} 就是进账方向 */
    private Boolean minusOnly;

    /** 备注关键词（对 {@code remark} 做前后都带 % 的模糊匹配），空白不过滤 */
    private String keyword;

    /** 时间范围起点（含），ISO 本地日期时间 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方按「有没有 userId」夹不同的上限 */
    private long size;
}
