package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台群成员页签的查询条件，由 im-admin 组装、im-group 执行。
 *
 * <p>这里<b>没有「包含已退群的人」这个开关</b>：{@code im_group_member} 一行一成员，
 * 退群只是把 {@code status} 置 0 而保留行（重新入群复用同一行以保留历史），
 * 所以「历史上有过哪些人」这件事本身是可查的，但把它做成默认视图会把
 * 「现在这个群有谁」这个 90% 场景的答案掺进一堆退群记录。本批只给在群成员。
 *
 * <p>{@code roleOnly} 用来一屏看清「这个群的管理层是谁」——排查违规群时第一个问题
 * 通常是「谁放进去的人」，而 {@code uk_group_user} 之外没有按角色筛的索引，
 * 所以它只在已经按群限定之后有意义。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminGroupMemberQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标群，必填 */
    private Long groupId;

    /** 只看某个角色：1 群主 2 管理员 3 普通成员；null 全部 */
    private Integer role;

    /** 昵称或群内昵称关键词（模糊），空白不过滤 */
    private String keyword;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限 */
    private long size;
}
