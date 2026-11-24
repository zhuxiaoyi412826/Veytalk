package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 用户与兴趣标签的一条绑定，对应表 {@code im_user_tag}（一行一个绑定）。
 *
 * <p>继承 {@link AuditEntity} 而不是 {@code BaseEntity}：这张表<b>没有</b> {@code deleted} 列，
 * 解绑就是删行——与 {@code im_friend} 同一口径，{@code uk_user_tag} 只有物理删除才会释放，
 * 取消后再重新选中同一个标签才能成功。
 *
 * <p>{@link #sort} 是用户自己拖的顺序（最重要的兴趣在前），资料卡与匹配卡片都按它展示。
 * 与照片墙不同，这里不需要「第一行有特殊身份」的不变量，所以整序逻辑简单得多。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user_tag")
public class UserTag extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 一个人最多能选几个标签。上限 10 而不是「不限制」：标签是要出现在卡片上的，
     * 放开了选会把资料卡刷成一片 chip，而完资料度的 15 分在 5 个就已经给满。
     */
    public static final int MAX_SELECT = 10;

    /** 完资料度里拿满 15 分所需的个数（少于上限，留出「够用就好」的余地） */
    public static final int SCORE_FULL = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** {@code im_tag.id} */
    private Long tagId;

    private Integer sort;
}
