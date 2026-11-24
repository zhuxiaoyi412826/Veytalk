package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台破冰字典的一行，对应 {@code im_icebreaker}。
 *
 * <p>{@link #tagName} 是为后台回填的展示字段：表里只有 {@code tag_id}，而运营面对一堆雪花 ID
 * 没法判断「这条开场白绑的是哪个标签」。绑定了已被删除的标签时给一个「标签已删除」的占位文案
 * 而不是留空——留空会让它看起来像一条通用文案，于是没人去修它。
 *
 * <p>{@link #content} 保留<b>原始占位符</b>（{@code {nickname}} / {@code {city}} / {@code {tag}}）：
 * 渲染是前台取建议时才做的，后台要看见的是模板本体。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminIcebreakerDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 文案类型：1 打招呼模板 2 话题卡 3 标签开场白 */
    private Integer type;

    /** 绑定的兴趣标签 ID，0 表示通用 */
    private Long tagId;

    /** 绑定标签的名字，{@code tagId=0} 时为空 */
    private String tagName;

    /** 文案原文，可含占位符 */
    private String content;

    /** 同 type 内排序，升序 */
    private Integer sort;

    /** 状态：1 启用 0 停用（停用只不再下发，不删行） */
    private Integer status;

    /** 被点击使用次数 */
    private Integer useCount;

    private LocalDateTime createTime;
}
