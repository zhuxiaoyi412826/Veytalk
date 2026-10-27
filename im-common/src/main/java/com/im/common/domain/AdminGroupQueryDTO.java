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
 * 后台群组台账的查询条件，由 im-admin 组装、im-group 执行。
 *
 * <p>排序固定在实现方：按群 ID（雪花）倒序，等于新建的群在前，走主键倒扫。
 * <b>不提供「按成员数排序」，也不提供「成员数不少于 N」这个筛选</b>：成员数不在
 * {@code im_group} 上，而在 {@code im_group_member} 里，全站按规模排要先把成员表
 * {@code GROUP BY group_id} 聚合一遍再排序，代价随成员行数增长而不是随页大小；
 * 一个筛 N 人以上的条件也躲不开同一次聚合。与其留一个「选了也要等三秒、
 * 还可能超时回空页」的下拉框，不如把它干脆不做，等成员表真的长到需要
 * {@code im_group.member_count} 这一冗余列时连同 DDL 一起补（本批零表结构变更）。
 * 页内倒是给了成员数（一页一次 {@code IN} 聚合），找大群现在靠翻页。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminGroupQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 群名关键词（模糊），空白不过滤 */
    private String keyword;

    /** 只看某个群主的群；0 或 null 不限 */
    private Long ownerId;

    /** 状态：1 正常 0 已解散；null 全部 */
    private Integer status;

    /** 建群时间范围起点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 建群时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限 */
    private long size;
}
