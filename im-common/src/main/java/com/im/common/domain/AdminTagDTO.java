package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台标签字典的一行，对应 {@code im_tag}。
 *
 * <p>{@link #useCount} 是<b>冗余计数</b>：它只服务「这个标签还有多少人在用」这一个运营判断与
 * 热门标签排序，漂移到多少都不影响业务——完资料度与同好反查一律按 {@code im_user_tag}
 * 的实际行数算。后台拿它决定「要不要下架一个没人选的标签」，而不是拿它拦删除。
 *
 * <p>删除是逻辑删（{@code deleted=1}），已绑定它的 {@code im_user_tag} 行保留：
 * 删一个标签不该把几百个人资料卡上的一栏抽掉，那用户既不知道发生了什么也无法恢复。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminTagDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 标签名，未删除行内全局唯一 */
    private String name;

    /** 所属分类，选择器按它分组 */
    private String category;

    /** 图标名，可空则前端吃分类默认图标 */
    private String icon;

    /** 同分类内排序，升序 */
    private Integer sort;

    /** 状态：1 启用 0 停用（停用只从选择器消失，已绑定的用户标签保留） */
    private Integer status;

    /** 被多少个用户选用，见类注释 */
    private Integer useCount;

    private LocalDateTime createTime;
}
