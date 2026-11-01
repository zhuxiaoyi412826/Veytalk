package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章板块展示视图。
 *
 * <p>比实体多两处：作者的昵称与头像（{@code im_article} 只存 author_id，展示时经
 * {@code UserQuerySpi} 补全，避免前端为每篇单独查用户）；以及 {@code mine} —— 由服务端
 * 判定当前登录用户是否作者，前端据此决定要不要给「编辑/删除」按钮，不信任前端自己比对。
 *
 * <p>广场列表接口出于响应体积考虑<b>不返回 {@code content}</b>（正文是 LONGTEXT）；只有详情接口
 * {@code GET /api/article/{id}} 才带正文。列表靠 {@code summary} 展示摘要。
 */
@Data
@Schema(description = "文章板块信息")
public class ArticleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "文章 ID")
    private Long id;

    @Schema(description = "作者用户 ID")
    private Long authorId;

    @Schema(description = "作者昵称")
    private String authorName;

    @Schema(description = "作者头像")
    private String authorAvatar;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "摘要")
    private String summary;

    @Schema(description = "Markdown 正文，仅详情接口返回，列表为 null")
    private String content;

    @Schema(description = "封面图受控访问地址，可空")
    private String coverUrl;

    @Schema(description = "封面图文件 ID，供编辑时回填以保留原封面（正文/封面本体在 im-file）")
    private Long coverFileId;

    @Schema(description = "状态：0 草稿 1 待审 2 已发布 3 驳回 4 已下架")
    private Integer status;

    @Schema(description = "驳回事由，仅 status=3 有值")
    private String rejectReason;

    @Schema(description = "浏览量")
    private Integer viewCount;

    @Schema(description = "发布/审核通过时间，未发布为 null")
    private LocalDateTime publishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "当前用户是否为作者")
    private Boolean mine;
}
