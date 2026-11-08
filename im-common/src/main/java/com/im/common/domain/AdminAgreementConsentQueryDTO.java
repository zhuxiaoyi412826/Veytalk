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
 * 后台同意留痕的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p>两个入口共用这一个条件对象：「按人查他完整的同意链」是 {@code userId} 填上、其余留空；
 * 「按版本查有多少人签过」是 {@code agreementId} 填上。工单里最常见的问法是第一种，
 * 监管抽查最常见的是第二种，拆成两个接口只会让前端抄两份分页代码。
 *
 * <p>排序写死同意时间倒序，不接受排序参数；单页上限由实现方夹。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAgreementConsentQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个用户：查「他到底同意过哪几版」 */
    private Long userId;

    /** 限定某个具体版本行：查「这一版有多少人签了」 */
    private Long agreementId;

    /** 限定协议标识 */
    private String code;

    /** 动作过滤：null 全部，1 同意 0 拒绝（拒签名单是增值服务投诉的第一站） */
    private Integer action;

    /** 时间范围起点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方夹上限 */
    private long size;
}
