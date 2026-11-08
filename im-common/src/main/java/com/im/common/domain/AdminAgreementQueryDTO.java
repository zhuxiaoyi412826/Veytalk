package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台协议版本列表的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p>与 {@link AdminArticleQueryDTO} 同一口径：不接受任何排序参数，
 * 实现方写死「按 code 升序 + version 倒序」——把 orderBy 交给 HTTP 参数等于把注入面递给外面。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAgreementQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 协议标识过滤：null 全部，privacy / terms / service */
    private String code;

    /** 状态过滤：null 全部，0 草稿 1 已发布 2 已归档 */
    private Integer status;

    /** 关键词：按标题模糊；协议版本行数是「改过多少次文案」的量级，全扫不亏 */
    private String keyword;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
