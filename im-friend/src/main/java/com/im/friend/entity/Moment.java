package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import com.im.common.enums.MomentVisibility;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 一条交友动态（{@code im_moment}）。
 *
 * <p>归属 im-friend 而不是 im-user：它的受众是「我的好友 ∪ 我配对过的人」，而 {@code im_friend}
 * 与 {@code im_match_pair} 两张关系表都在 im-friend。放到 im-user 就得为「这个人是不是我的受众」
 * 再开一条反向 SPI，而那个判定每次刷 Feed 都要跑一遍。
 *
 * <p>与 {@code im_article} 刻意分开：文章是长内容、走审核状态机、进公开广场；动态是社交信号，
 * 有可见范围、只在熟人之间流转。合成一张表就得让「可见范围」这个只在动态上有意义的列
 * 挂在所有文章行上，而 {@code idx_status_time} 也会因为两种排序口径而失去意义。
 *
 * <p>{@link #likeCount} / {@link #commentCount} / {@link #viewCount} 是<b>冗余计数</b>，写时增减：Feed 一屏十几条，
 * 每条都 {@code COUNT(*)} 一次点赞与评论是两次全表扫。代价是它可能与附属表行数漂移，
 * 所以减法一律走 {@code GREATEST(x - 1, 0)}，漂移的后果只是「显示的数字少一」而不是负数。
 * 浏览量没有附属表，它只是一个「详情页打开次数」的累加器，口径与 {@code im_article.view_count} 一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_moment")
public class Moment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：正常，会进 Feed */
    public static final int STATUS_NORMAL = 1;
    /**
     * 状态：已下架。<b>行保留</b>，只是不再出现在任何人的 Feed 与详情里：
     * 后台下架一条动态后还要能查到是谁发的、发了什么，物理删掉就只剩下举报工单里的一句话。
     */
    public static final int STATUS_OFFLINE = 2;

    /** 正文字符上限。DDL 的列宽是 1024，这里刻意只放到 500：动态是刷过去的东西 */
    public static final int CONTENT_MAX = 500;

    /** 一条动态最多几张图 */
    public static final int MAX_IMAGES = 9;
    /** 一条动态最多几个视频：有视频就不允许再有图，两者不混排（九宫格画不下一格视频） */
    public static final int MAX_VIDEOS = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 发布用户 ID */
    private Long userId;

    /** 正文，可空（纯图动态）；上限 {@link #CONTENT_MAX} 个字符 */
    private String content;

    /** 可见范围，取值见 {@link MomentVisibility} */
    private Integer visibility;

    /** 媒体条数（冗余，卡片据此决定画一格还是九宫格） */
    private Integer mediaCount;

    private Integer likeCount;

    /** 含一级评论与其下的回复 */
    private Integer commentCount;

    /** 详情页每次打开 +1，非去重（口径同 {@code im_article.view_count}）；作者本人打开不计数 */
    private Integer viewCount;

    /** 状态：1 正常 2 已下架 */
    private Integer status;

    /** 这一条对 {@code viewer} 可见吗。可见性的全部判据都收在这一个方法里，见方法注释。 */
    public boolean visibleTo(Long viewer, boolean isFriend, boolean isMatched, boolean whitelisted) {
        if (viewer == null || viewer.equals(userId)) {
            // 自己的动态永远看得见：否则「仅自己」这一档会把作者本人也挡在外面
            return true;
        }
        MomentVisibility scope = MomentVisibility.of(visibility);
        if (scope == null) {
            // 脏值不退到「公开」：这一列被手改成一个没人认识的值时，宁可少给
            return false;
        }
        return switch (scope) {
            case PUBLIC -> isFriend || isMatched;
            case FRIENDS_ONLY -> isFriend;
            case PARTIAL -> whitelisted;
            case SELF_ONLY -> false;
        };
    }

    public boolean isNormal() {
        return status != null && status == STATUS_NORMAL;
    }
}
