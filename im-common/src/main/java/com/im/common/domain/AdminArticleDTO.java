package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理后台用的文章档案，由 im-live 经 {@code AdminArticleSpi} 提供。
 *
 * <p>与用户端的 {@code ArticleVO} 分开的理由和 {@link AdminVideoDTO} 一样：用户端广场只看得到
 * status=2 的已发布文章且带「是否本人」标志，后台要看到全状态（草稿/待审/已发布/驳回/已下架）
 * 且能按作者检索，两套口径不同不能共用一个 VO。
 *
 * <p><b>{@link #content} 只在详情方法里回填，分页列表为 null</b>——正文是 LONGTEXT，一屏 100 行
 * 全带上正文会把响应撑爆；列表只要标题/摘要/状态这些轻字段，运营点开某条再单独取详情。
 *
 * <p>{@link #coverUrl} 是服务端拼好的长期受控地址（非预签名短链），后台预览直接引用不会几分钟就 404。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminArticleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文章 ID（im_article 主键） */
    private Long id;

    /** 作者用户 ID */
    private Long authorId;

    /** 作者昵称（查不到时实现方给「已注销用户」占位，不留 null） */
    private String authorName;

    /** 标题 */
    private String title;

    /** 列表摘要，可为空 */
    private String summary;

    /** Markdown 正文：仅详情回填，分页为 null */
    private String content;

    /** 封面受控访问地址，可为空 */
    private String coverUrl;

    /** 状态：0 草稿 1 待审核 2 已发布 3 已驳回 4 已下架（与 im_article.status 同口径） */
    private Integer status;

    /** 驳回事由，仅 status=3 时有意义 */
    private String rejectReason;

    /** 浏览量 */
    private Long viewCount;

    /** 审核通过/发布时间，未发布为 null */
    private LocalDateTime publishTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
