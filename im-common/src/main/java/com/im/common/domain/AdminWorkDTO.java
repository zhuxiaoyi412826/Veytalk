package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台用的「图片 / 音乐 / 小说」作品档案，由 im-live 经 {@code AdminWorkSpi} 提供。
 *
 * <p>三板块共用一个 DTO 是刻意的：{@code WorkManage.vue} 也因此只有一份。三张表的列口径
 * 本来就在 {@code sql/im_schema.sql} 里对齐过（包括计数列统一叫 {@code playCount}），
 * 不适用的字段留 {@code null}——前端按 {@code type} 决定显哪几列，不做「三个板块三套字段名」的映射。
 *
 * <p>与用户端 VO 分开的理由同 {@link AdminVideoDTO}：后台要看得到已下架的行与内部 ID，
 * 用户端只看得到 status=1。
 *
 * <p>三个 URL 字段都是服务端按 {@code im.file.access-url-prefix} 现拼的受控访问地址，
 * 不采信调用方传来的值，也不塞短时效的预签名地址（工单式翻页停留几分钟后 404 的预览链接不如没有）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWorkDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 作品 ID（对应板块表的主键） */
    private Long id;

    /** 所属板块：image / music / novel（{@code com.im.common.enums.WorkBoardType} 的 code） */
    private String type;

    /** 标题 */
    private String title;

    /** 分类文本（与各板块 {@code category} 列一致；分类字典改名的历史行保持原文，不回填） */
    private String category;

    /** 作者用户 ID */
    private Long uploaderId;

    /** 作者昵称（查不到时实现方给「已注销用户」占位，不留 null；后台代建的作品是管理员昵称） */
    private String uploaderName;

    /** 摘要：小说是列表摘要（空则服务端从正文截取），图片/音乐为标题占位，可为空 */
    private String summary;

    /** 图集文件 ID（与 {@link #imageUrls} 同序），只有图片板块非空；后台编辑图集要靠它回填已选图 */
    private List<Long> imageIds;

    /** 图集受控地址，顺序即展示顺序；只有图片板块非空 */
    private List<String> imageUrls;

    /** 图集张数（1~9）；只有图片板块有值 */
    private Integer imageCount;

    /** 音频受控地址；只有音乐板块有值 */
    private String fileUrl;

    /** 封面受控地址（图片板块等于首图地址），可为空 */
    private String coverUrl;

    /** 时长（秒）；只有音乐板块有值 */
    private Integer duration;

    /** 正文字数（服务端按去空白字符数算）；只有小说板块有值 */
    private Integer wordCount;

    /** Markdown 正文：<b>只有 {@code detail} 取小说时才有值</b>，列表与其它板块恒为 null */
    private String content;

    /** 字节数（图集为各图合计，小说为正文字节数） */
    private Long size;

    /** 计数：图片=打开次数、音乐=播放次数、小说=阅读次数（三表同一列名 play_count，非去重） */
    private Integer playCount;

    /** 状态：1 正常 2 已下架（与 {@code im_live_video.status} 同口径，不走文章的审核状态机） */
    private Integer status;

    /** 发布时间 */
    private LocalDateTime createTime;

    /** 最后改动时间 */
    private LocalDateTime updateTime;
}
