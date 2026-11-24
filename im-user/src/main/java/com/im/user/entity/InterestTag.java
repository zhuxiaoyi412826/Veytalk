package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 兴趣标签库的一条标签，对应表 {@code im_tag}（后台可维护的公共字典）。
 *
 * <p>类名刻意不叫 {@code Tag}：本模块每个 Controller 都要 import Swagger 的
 * {@code io.swagger.v3.oas.annotations.tags.Tag}，同名实体一旦出现在同一个文件里就得处处写全限定名。
 *
 * <p>{@link #useCount} 是<b>冗余</b>计数，由标签绑定/解绑时增减，不是实时算出来的：
 * 标签库要按热度排序、后台要看「多少个在用」，每次列表都 {@code COUNT(*) GROUP BY tag_id}
 * 一次是不划算的。代价是它可能因异常中断而漂移，所以读它只用于展示与排序，
 * <b>绝不参与完资料度或匹配打分</b>（那些一律按 {@code im_user_tag} 实际行数走）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_tag")
public class InterestTag extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：启用，会出现在选择器里 */
    public static final int STATUS_ENABLED = 1;
    /**
     * 状态：停用。<b>只从选择器里消失，已绑定的用户标签保留</b>——后台下架一个标签
     * 不该把几百个人资料卡上的一栏直接删掉一行，那用户既不知道发生了什么也无法恢复。
     */
    public static final int STATUS_DISABLED = 0;

    /** 没有分类时的兜底归属，与建表默认值一致 */
    public static final String DEFAULT_CATEGORY = "其他";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 标签名，全局唯一（{@code uk_name}），前端 chip 的文案 */
    private String name;

    private String category;

    /** 图标名，可空则前端吃分类默认图标 */
    private String icon;

    /** 同分类内排序，升序 */
    private Integer sort;

    private Integer status;

    /** 被多少个用户选用，见类注释 */
    private Integer useCount;
}
