package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 一条动态评论（{@code im_moment_comment}）。
 *
 * <p><b>两层封顶</b>：{@link #parentId} 为 {@link #PARENT_ROOT} 是一级评论，非 0 则挂在某条一级评论下面。
 * 回复一条回复时，{@code parentId} 仍然写那条<b>一级</b>评论的 ID，被回复的人记在
 * {@link #replyUserId} 上，前端渲染成「A 回复 B」。
 *
 * <p>不做无限嵌套的理由：无限层要么每层缩进（手机上第三层就只剩半行字宽），要么把子树压平——
 * 而压平之后 {@code parentId} 就退化成本类的两层模型，多出来的一列永远读不出层次。
 * 交友动态的评论量本来就小，两层的表达力足够。
 *
 * <p>有 {@code deleted} 列，继承 {@link BaseEntity}：删一条一级评论时它下面的回复跟着置 1，
 * 否则会出现「回复了一条已经不存在的评论」这种只能靠前端兜底的悬挂结构。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_moment_comment")
public class MomentComment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** {@link #parentId} 的这个值表示一级评论（与建表默认值一致） */
    public static final long PARENT_ROOT = 0L;

    /** 状态：正常 */
    public static final int STATUS_NORMAL = 1;
    /** 状态：已下架（后台处置），行保留但不下发内容 */
    public static final int STATUS_OFFLINE = 2;

    /** 评论字符上限，与列宽一致 */
    public static final int CONTENT_MAX = 500;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long momentId;

    /** 评论人 ID */
    private Long userId;

    /** 父评论 ID：0 一级评论，非 0 为某条一级评论下的回复 */
    private Long parentId;

    /** 被回复人 ID，只有 {@link #parentId} 非 0 时有值 */
    private Long replyUserId;

    private String content;

    /**
     * 评论点赞数。<b>本批没有写入路径</b>：评论点赞不建表（建表注释里写明了取舍），
     * 没有记录就无法区分「点过」与「没点过」，也就无法取消，所以这一列恒为 0，
     * 也因此不下发给前端——下发一个永远是 0 的字段只会让人以为「点了没反应」。
     */
    private Integer likeCount;

    /** 状态：1 正常 2 已下架 */
    private Integer status;

    /** 是不是一级评论。 */
    public boolean isRoot() {
        return parentId == null || parentId == PARENT_ROOT;
    }

    public boolean isNormal() {
        return status != null && status == STATUS_NORMAL;
    }
}
