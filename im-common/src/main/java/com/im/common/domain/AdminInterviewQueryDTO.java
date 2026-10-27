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
 * 后台面试记录的查询条件，由 im-admin 组装、im-ai 执行。
 *
 * <p>排序固定在实现方：进行中的（{@code status=0}）顶到最前，其余按 {@code (create_time, id)} 倒序。
 * 这一屏不开「按违规数排序」：那需要对全表做文件排序，而 {@code im_interview_session} 只有
 * {@code idx_user_time} 与 {@code idx_status} 两条索引。真要看「谁违规最多」，
 * 用 {@code violationOnly} 或 {@code minViolations} 把范围收窄，剩下的几十行人自己数。
 *
 * <p>{@code userId} 是审计场景最常用的入口（有人举报某场面试作弊、或候选人申诉被判违规），
 * 命中 {@code idx_user_time}；{@code status} 命中 {@code idx_status}。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminInterviewQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个候选人 */
    private Long userId;

    /** 状态精确过滤：0 进行中 1 正常结束 2 违规达阈值强制结束 3 未完成 */
    private Integer status;

    /** 只看有过违规记录（{@code violation_count > 0}）的场次 */
    private Boolean violationOnly;

    /** 违规次数下限，与 {@code violationOnly} 同时给时本项优先 */
    private Integer minViolations;

    /** 岗位/题目名称关键词（模糊），空白不过滤 */
    private String keyword;

    /** 开考时间范围起点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 开考时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限 */
    private long size;
}
