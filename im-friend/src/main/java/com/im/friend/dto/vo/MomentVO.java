package com.im.friend.dto.vo;

import com.im.common.domain.UserBriefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条动态。
 *
 * <p>作者是 {@link UserBriefDTO} 而不是 {@code DatingCardDTO}：卡片那套隐私收口
 * （年龄隐藏、距离模糊化、在线位对陌生人关闭）是为「陌生人第一次看到这个人」准备的，
 * 而动态的作者恒在我的受众里（好友或配对对象），这两种人本来就能在会话列表里看到彼此的在线位。
 * 更要紧的是成本——一屏十几条动态各拼一张卡，就是十几份资料 + 照片 + 标签查询，
 * 而 Feed 上只需要一个头像与一个昵称。
 *
 * <p>{@link #comments} 在 Feed 里只带前几条，在详情里带全部；{@link #commentCount} 恒为权威总数，
 * 两者不相等时前端显示「还有 N 条评论」。分开下发是因为「带几条」是列表的性能取舍，
 * 而「一共几条」是内容本身。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友动态")
public class MomentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "动态 ID，同时是下一页的游标")
    private Long momentId;

    @Schema(description = "作者精简信息（昵称 / 头像）")
    private UserBriefDTO author;

    @Schema(description = "正文，纯图动态为空")
    private String content;

    @Schema(description = "可见范围：1 公开（好友与配对对象可见） 2 仅好友 3 部分人可见 4 仅自己；只对作者本人下发")
    private Integer visibility;

    @Schema(description = "可见范围文案；只对作者本人下发，别人看到这一标等于从侧面知道了自己与作者的好友关系")
    private String visibilityDesc;

    @Schema(description = "媒体条数（冗余，卡片据此决定画一格还是九宫格）")
    private Integer mediaCount;

    @Schema(description = "附件列表，按九宫格顺序升序")
    private List<MomentMediaVO> media;

    @Schema(description = "点赞数")
    private Integer likeCount;

    @Schema(description = "评论数（含回复），权威总数")
    private Integer commentCount;

    @Schema(description = "浏览量（详情页每打开一次 +1，非去重）；只对作者本人下发，与可见范围同一口径")
    private Integer viewCount;

    @Schema(description = "我是否赞过这一条")
    private Boolean liked;

    @Schema(description = "评论列表（两层：一级 + 回复）；Feed 只带前几条，详情带全部")
    private List<MomentCommentVO> comments;

    @Schema(description = "发布时间，Feed 按它（实际按 ID）倒序")
    private LocalDateTime createTime;

    @Schema(description = "状态：1 正常 2 已下架；只对作者本人下发")
    private Integer status;

    @Schema(description = "状态文案；只对作者本人下发")
    private String statusDesc;

    @Schema(description = "这一条是不是我发的（决定能不能删）")
    private Boolean mine;
}
