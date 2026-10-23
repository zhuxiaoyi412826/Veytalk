package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台短视频列表的查询条件，由 im-admin 组装、im-live 执行。
 *
 * <p>与 {@link AdminUserQueryDTO} 同一口径：分页参数不带排序白名单，
 * 排序写死为发布时间倒序——把 orderBy 交给 HTTP 参数等于把注入面递给外面。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminVideoQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按标题 / 上传者用户 ID 精确或模糊匹配，口径由实现方定并写进注释 */
    private String keyword;

    /** 状态过滤：null 全部，1 正常，2 已下架 */
    private Integer status;

    /** 上传者过滤：null 不限 */
    private Long uploaderId;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
