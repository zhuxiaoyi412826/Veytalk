package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台评论列表的一行，对应 {@code im_moment_comment}。
 *
 * <p>{@link #momentSummary} 是给审核人判断语境用的动态摘要（不是完整正文）：一条骂人的回复
 * 脱离上下文就没法定性，而为它单独跳一次详情是白跑一趟。要看全文由前端拿 {@code momentId}
 * 再调一次详情，列表不为全文付带宽。
 *
 * <p>回复行（{@code parentId != 0}）与一级评论平铺在列表里，不组树：跨动态的审核队列里
 * 一棵子树可能被切到两页，组树反而做出「父不见了只剩子」的残缺展示。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMomentCommentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 所属动态 ID */
    private Long momentId;

    /** 所属动态的正文摘要 */
    private String momentSummary;

    private Long userId;

    /** 评论者昵称，查不到时给「已注销用户」占位 */
    private String userNickname;

    /** 父评论 ID，0 表示一级评论 */
    private Long parentId;

    /** 被回复的用户 ID，只有回复行有值 */
    private Long replyUserId;

    /** 被回复者的昵称，回复行才给 */
    private String replyUserNickname;

    private String content;

    private Integer likeCount;

    /** 状态：1 正常 2 已下架 */
    private Integer status;

    private LocalDateTime createTime;
}
