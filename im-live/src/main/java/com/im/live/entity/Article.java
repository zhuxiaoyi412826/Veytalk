package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 直播页「文章」板块的一篇文章，对应 {@code im_article}。
 *
 * <p>与 {@link LiveVideo} 一样是「控制面」数据：正文里的图片与封面本体存在 {@code im_file}
 * （bizType = article_img / article_cover，对所有登录用户公开），本表存 Markdown 原文、作者、
 * 审核状态与浏览量。<b>一篇文章一行</b>。
 *
 * <p>状态是一台带审核的小状态机（{@link #STATUS_DRAFT}/{@link #STATUS_PENDING}/
 * {@link #STATUS_PUBLISHED}/{@link #STATUS_REJECTED}/{@link #STATUS_OFFLINE}）：作者存草稿或提交审核，
 * 管理员通过/驳回，或从已发布下架。对外广场只认 {@link #STATUS_PUBLISHED} 这一态。
 *
 * <p>{@link #coverUrl} 存的是长期受控地址 {@code /api/file/download/{id}}，前端经 {@code mediaUrl}
 * 取成 blob 再渲染；正文里的图片以同样的受控地址写进 Markdown，渲染时统一转 blob，绝不落票据直链。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_article")
public class Article extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：草稿，仅作者可见，可继续编辑/提交 */
    public static final int STATUS_DRAFT = 0;
    /** 状态：待审核，作者已提交，公开列表不展示，仅作者与管理员可见 */
    public static final int STATUS_PENDING = 1;
    /** 状态：已发布，审核通过，广场展示（唯一对外可见态） */
    public static final int STATUS_PUBLISHED = 2;
    /** 状态：已驳回，带 {@link #rejectReason}，仅作者可见，可改后重新提交 */
    public static final int STATUS_REJECTED = 3;
    /** 状态：已下架，管理员从已发布下架，记录保留可恢复 */
    public static final int STATUS_OFFLINE = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 作者用户 ID */
    private Long authorId;

    private String title;

    /** 列表摘要，空则服务端从正文截取 */
    private String summary;

    /** Markdown 原文 */
    private String content;

    /** 封面图文件 ID（im_file.id，bizType=article_cover），可空 */
    private Long coverFileId;

    /** 封面图受控访问地址，可空（前端退化为占位图） */
    private String coverUrl;

    /** 审核状态，见上方常量 */
    private Integer status;

    /** 驳回事由，仅 status=3 有意义 */
    private String rejectReason;

    /** 浏览量（详情页每次打开 +1，非去重） */
    private Integer viewCount;

    /** 审核通过/发布时间，未发布为 null */
    private LocalDateTime publishTime;

    /** 是否本人所写（决定前端能否编辑/删除） */
    public boolean ownedBy(Long userId) {
        return userId != null && userId.equals(authorId);
    }
}
