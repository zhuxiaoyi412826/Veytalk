package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台交友资料列表的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p>与 {@link AdminVideoQueryDTO} 同一口径：不给排序白名单，翻页固定按最近活跃倒序——
 * 把 orderBy 交给 HTTP 参数等于把注入面递给外面，而运营真正要的只有「最不活跃的那批人」。
 *
 * <p>没有「按年龄区间」这一档筛选：{@code im_user_profile} 存的是 {@code birthday} 而不是年龄列，
 * 按年龄过滤要在 SQL 里算 {@code TIMESTAMPDIFF}，那一列上的索引就用不上了。生日本来也不该
 * 被当成检索条件——它是隐私，不是运营的工作维度。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminDatingQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按城市 / 职业模糊匹配，纯数字时额外认用户 ID，口径由实现方定 */
    private String keyword;

    /** 用户 ID 精确过滤：运营从工单里拿到的往往就是它，与关键词并存时取交集 */
    private Long userId;

    /** 城市过滤（包含匹配，与「同城」同一判据）；null 不限 */
    private String city;

    /** 隐身过滤：null 全部，1 可被发现，0 已隐身（含用户自设与后台强制） */
    private Integer discoverable;

    /** 推荐质量标记：null 全部，0 正常 1 低质降权 2 已升权 */
    private Integer qualityFlag;

    /** 完资料度下界：只看填到某个程度以上的人；null 不限 */
    private Integer minScore;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
