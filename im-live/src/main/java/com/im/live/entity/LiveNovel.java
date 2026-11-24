package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 直播页「小说」板块的作品，对应 {@code im_live_novel}，<b>一行一篇</b> Markdown 正文。
 *
 * <p>与 {@link com.im.live.entity.Article} 的区别只有两点：<b>不走审核状态机</b>（发布即
 * {@link #STATUS_NORMAL} 可见，只有 1 正常 / 2 已下架，与视频/图片/音乐同一口径），
 * 以及多了分类与阅读计数。文章的「草稿/待审/驳回」那套生命周期刻意没有下沉到这里：
 * 三板块是信息流内容，把它拧成审核流会让前端凭空多出一套状态展示逻辑。
 *
 * <p><b>{@link #content} 是 LONGTEXT，列表查询绝不能带上它</b>：一屏 15 篇长文就能拖回几百 MB，
 * 还会把覆盖索引变成回表。服务层的分页查询一律用
 * {@code LambdaQueryWrapper.select(...)} 显式列出要取的列、把正文排除在外，只有详情接口
 * （{@code selectById}）取全文。这条是本板块最容易埋的性能雷，改动查询时先看这里。
 *
 * <p>{@link #wordCount} 与 {@link #size} 都由服务端现算（去空白字符数 / UTF-8 字节数），
 * <b>不采信前端上报</b>：字数是列表上要显示的数，报错了用户第一眼就看到。
 * {@link #summary} 留空时由服务端从正文截取，保证卡片不会因为作者没填摘要就空一块。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_live_novel")
public class LiveNovel extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：正常（在信息流展示） */
    public static final int STATUS_NORMAL = 1;
    /** 状态：已下架（不展示） */
    public static final int STATUS_OFF = 2;

    /**
     * 正文字符上限：约等于一部中篇小说的量。超限直接拒绝并提示分篇，
     * 而不是悄悄截断——截断等于把作者的稿子吃掉了，那比报错严重得多。
     */
    public static final int CONTENT_MAX = 200_000;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 作者用户 ID */
    private Long uploaderId;

    private String title;

    /** 分类（小说板块二级分类标签，如 玄幻/悬疑推理…），缺省 综合 */
    private String category;

    /** 列表摘要，空则服务端从正文截取前 80 字 */
    private String summary;

    /** Markdown 正文（列表接口不返回，只在详情取） */
    private String content;

    /** 字数，服务端按正文去空白后的字符数现算 */
    private Integer wordCount;

    /** 封面图文件 ID（bizType=novel_cover），可空 */
    private Long coverFileId;

    /** 封面图受控访问地址，可空（前端退化为占位图） */
    private String coverUrl;

    /** 正文字节数（UTF-8），服务端算 */
    private Long size;

    /** 阅读次数（每次打开详情 +1，非去重） */
    private Integer playCount;

    private Integer status;

    /** 是否本人上传（决定前端能否编辑/删除） */
    public boolean ownedBy(Long userId) {
        return userId != null && userId.equals(uploaderId);
    }
}
