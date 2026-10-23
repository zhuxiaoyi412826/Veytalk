package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台用户列表的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p>分页参数用 long 而不复用 {@code PageQuery}：那个带了排序字段与白名单校验，
 * 后台列表的排序口径（注册时间倒序）由实现方写死更安全——
 * 把 orderBy 交给 HTTP 参数等于把 SQL 注入面递给外面。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按账号 / 昵称 / 手机号前缀模糊匹配，为空不过滤 */
    private String keyword;

    /** 账号状态过滤：null 全部，1 正常，0 已禁用 */
    private Integer status;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
