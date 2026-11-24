package com.im.friend.dto.req;

import com.im.friend.entity.MomentComment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 在一条动态下评论 / 回复。
 *
 * <p>{@link #parentId} 为空即一级评论。回复<b>一条回复</b>时也传那条<b>一级</b>评论的 ID，
 * 被回复的人写在 {@link #replyUserId} 上——两层封顶（口径见 {@link MomentComment} 的类注释），
 * 服务端会按 {@code parentId} 反查真正的被回复人，所以 {@code replyUserId} 传错也不会串人。
 *
 * <p>正文必填：一条空评论在 UI 上是一行只有头像与时间的空位，而它照样计入 {@code comment_count}。
 */
@Data
@Schema(description = "动态评论请求")
public class MomentCommentRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "评论内容", example = "海边好看！")
    @NotBlank(message = "评论不能为空")
    @Size(max = MomentComment.CONTENT_MAX, message = "评论最多 " + MomentComment.CONTENT_MAX + " 字")
    private String content;

    @Schema(description = "父评论 ID：为空表示一级评论；回复某条回复时也传它所在的那条一级评论 ID",
            example = "194823748239849473")
    private Long parentId;

    @Schema(description = "被回复人 ID，仅回复时有意义；服务端按 parentId 复核，传错不会串人")
    private Long replyUserId;
}
