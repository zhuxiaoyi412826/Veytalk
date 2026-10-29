package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 新建 / 编辑文章请求（作者侧）。
 *
 * <p>正文里的图片与封面本体的上传复用通用 {@code im-file}（{@code /api/file/upload} 与分片接口，
 * bizType 传 article_img / article_cover），前端把上传返回的 fileId 拼成受控地址写进 Markdown，
 * 本请求只承载「标题 + 摘要 + Markdown 原文 + 封面 fileId + 动作」。<b>不传状态</b>：状态由
 * {@link #action} 与服务端规则决定，杜绝客户端把自己文章直接伪造成已发布。
 *
 * <p>{@link #action}：{@code draft} 存草稿（status=0）或 {@code submit} 提交审核（status=1）。
 * 编辑已驳回文章时同样用它决定改完是继续搁置还是重新送审。
 */
@Data
@Schema(description = "新建/编辑文章请求")
public class ArticleSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动作：存草稿 */
    public static final String ACTION_DRAFT = "draft";
    /** 动作：提交审核 */
    public static final String ACTION_SUBMIT = "submit";

    @Schema(description = "文章标题", example = "Markdown 排版指南")
    @Size(max = 120, message = "文章标题最多 120 字")
    private String title;

    @Schema(description = "列表摘要，可空——为空时服务端从正文截取")
    @Size(max = 255, message = "摘要最多 255 字")
    private String summary;

    @Schema(description = "Markdown 正文原文")
    @NotBlank(message = "文章正文不能为空")
    private String content;

    @Schema(description = "封面图文件 ID（已通过 bizType=article_cover 上传），可空")
    private Long coverFileId;

    @Schema(description = "动作：draft 存草稿 / submit 提交审核", example = "submit")
    @NotBlank(message = "动作不能为空")
    @Size(max = 16, message = "动作取值非法")
    private String action;
}
