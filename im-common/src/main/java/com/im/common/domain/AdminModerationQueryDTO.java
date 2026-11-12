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
 * 后台审核记录检索的查询条件，由 im-admin 组装、im-file 执行（五期·路线 2）。
 *
 * <p>与消息检索那张表不同，这里<b>没有「带关键词必须带范围」的硬约束</b>：
 * {@code im_moderation_log} 是低频流水表（命中才有行，上报端限流每分钟 30 次/用户），
 * 按主键倒序取一页永远便宜，什么都不选直接点搜索也只是「最近 N 条」，不心疼。
 *
 * <p>排序固定按主键倒序（雪花 ID 即时间序），不接受外部排序参数——
 * 这一屏没有「按时间正序看历史」的真实场景，而 {@code create_time} 上没有索引，
 * 拿它排序在只带时间范围时要对整个命中集 filesort。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminModerationQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个上报用户 */
    private Long userId;

    /** 业务场景精确过滤：avatar / chat_image / video_cover；null 全部 */
    private String scene;

    /** 命中类别精确过滤：Porn / Hentai / Violent；null 全部 */
    private String hitClass;

    /** 命中时间范围起点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 命中时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方夹到 50 */
    private long size;
}
