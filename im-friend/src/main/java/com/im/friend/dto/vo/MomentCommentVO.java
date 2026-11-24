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
 * 一条评论，<b>两层封顶</b>：顶层列表只放一级评论，回复挂在 {@link #replies} 里。
 *
 * <p>下发嵌套结构而不是平铺 + {@code parentId}：平铺的话前端每次渲染都要自己分一次组，
 * 而「回复挂在谁下面」这件事服务端本来就已经算过一遍了（删一级评论要连子树一起删），
 * 再让前端算一次就是两处口径，漏的那一处会表现成「回复飘在评论区最下面」。
 *
 * <p>{@code replyUser} 是快照而不是 ID：「A 回复 B」这句话里的 B 要显示昵称，
 * 而昵称在 im-user，让前端为每条评论再查一次用户是不可接受的往返数。
 *
 * <p>{@code likeCount} 不下发：评论点赞没有记录表，也就无法取消，
 * 一个永远是 0 的字段只会让人以为「点了没反应」（口径写在 {@code MomentComment#likeCount} 上）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "动态评论")
public class MomentCommentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "评论 ID，删除时提交它")
    private Long commentId;

    @Schema(description = "所属动态 ID")
    private Long momentId;

    @Schema(description = "评论人精简信息（昵称 / 头像）")
    private UserBriefDTO user;

    @Schema(description = "父评论 ID，0 表示一级评论")
    private Long parentId;

    @Schema(description = "被回复人精简信息，仅回复时有值")
    private UserBriefDTO replyUser;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "评论时间")
    private LocalDateTime createTime;

    @Schema(description = "这条评论是不是我发的（决定能不能删）")
    private Boolean mine;

    @Schema(description = "挂在下面的回复，按时间升序；一级评论才有值")
    private List<MomentCommentVO> replies;
}
