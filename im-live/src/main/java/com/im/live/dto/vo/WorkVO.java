package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 直播页「图片 / 音乐 / 小说」三板块共用的信息流视图。
 *
 * <p><b>刻意只有一个 VO</b>：三张表的列口径本来就在 {@code sql/im_schema.sql} 里对齐过
 * （计数列统一叫 {@code play_count}），再做三个 VO，前端就得写三份「哪个板块有哪些字段」的
 * 映射，而 {@code WorkFeed.vue} 本来就是一份组件按 {@code type} 分支渲染。不适用的字段留
 * {@code null}，前端按板块显该显的。视频板块仍用它自己的 {@link LiveVideoVO}，不做迁移。
 *
 * <p>比实体多两处，与 {@link LiveVideoVO} 同口径：上传者昵称与头像经 {@code UserQuerySpi} 补全
 * （不让前端为每张卡片单独查用户）；{@code mine} 由服务端判定当前登录用户是否上传者，
 * 前端据此决定给不给「编辑/删除」按钮——<b>不信任前端自己比对</b>，服务端同样在写接口里再校验一次。
 *
 * <p>{@code fileUrl} / {@code coverUrl} / {@code imageUrls} 都是服务端现拼的长期受控地址，
 * 前端经 {@code mediaUrl} 取 blob 渲染，绝不落票据直链。
 *
 * <p>{@code content} <b>只有小说详情接口才有值</b>：列表一律不带 LONGTEXT 正文，
 * 所以前端点开某一篇时要另调 {@code GET /api/live/novel/{id}}。
 */
@Data
@Schema(description = "作品板块（图片/音乐/小说）信息")
public class WorkVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "作品 ID")
    private Long id;

    @Schema(description = "所属板块：image / music / novel")
    private String type;

    @Schema(description = "作者用户 ID")
    private Long uploaderId;

    @Schema(description = "作者昵称")
    private String uploaderName;

    @Schema(description = "作者头像")
    private String uploaderAvatar;

    @Schema(description = "作品标题")
    private String title;

    @Schema(description = "分类标签，缺省 综合")
    private String category;

    @Schema(description = "摘要（小说为正文截取，其它板块可空）")
    private String summary;

    @Schema(description = "图集文件 ID 列表（与 imageUrls 同序），只有图片板块有值；编辑弹窗要靠它回填已选图")
    private List<Long> imageIds;

    @Schema(description = "图集受控地址列表（顺序即展示顺序），只有图片板块有值")
    private List<String> imageUrls;

    @Schema(description = "图集张数（1~9），只有图片板块有值")
    private Integer imageCount;

    @Schema(description = "音频文件 ID，只有音乐板块有值")
    private Long fileId;

    @Schema(description = "音频受控访问地址，只有音乐板块有值")
    private String fileUrl;

    @Schema(description = "封面文件 ID（图片板块等于首图 ID），可空")
    private Long coverFileId;

    @Schema(description = "封面受控访问地址（图片板块等于首图地址），可空")
    private String coverUrl;

    @Schema(description = "时长（秒），只有音乐板块有值")
    private Integer duration;

    @Schema(description = "正文字数（服务端按去空白字符数算），只有小说板块有值")
    private Integer wordCount;

    @Schema(description = "Markdown 正文：<b>只有小说详情接口返回</b>，列表与其它板块恒为 null")
    private String content;

    @Schema(description = "字节数（图集为各图合计，小说为正文字节数）")
    private Long size;

    @Schema(description = "扩展名，不含点（图片板块为首图）")
    private String ext;

    @Schema(description = "MIME 类型（图片板块为首图）")
    private String contentType;

    @Schema(description = "计数：图片=打开次数、音乐=播放次数、小说=阅读次数")
    private Integer playCount;

    @Schema(description = "状态：1 正常 2 已下架")
    private Integer status;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "当前用户是否为作者")
    private Boolean mine;
}
