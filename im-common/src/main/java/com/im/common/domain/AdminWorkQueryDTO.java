package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台作品列表的查询条件（图片 / 音乐 / 小说共用），由 im-admin 组装、im-live 执行。
 *
 * <p>与 {@link AdminVideoQueryDTO} 同一口径：分页参数之外不接受任何排序表达式，
 * 排序写死发布时间倒序——把 orderBy 交给 HTTP 参数等于把注入面递给外面。
 *
 * <p>{@link #type} 是这一套通用契约与「三张独立表」之间唯一的桥：实现方按它分发到对应 Service，
 * 不在白名单（{@code video} 与未知值）里的一律拒绝，而不是退回「查全部」。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWorkQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 板块：image / music / novel（必填） */
    private String type;

    /** 关键词：按标题模糊匹配，另可传纯数字按作品 ID 精确命中，口径由实现方落进注释 */
    private String keyword;

    /** 状态过滤：null 全部，1 正常，2 已下架 */
    private Integer status;

    /** 分类文本过滤：null 不限（取值来自分类字典，但比对的是作品行的 category 原文） */
    private String category;

    /** 作者过滤：null 不限（后台「只看某个用户上传的图集」用） */
    private Long uploaderId;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
