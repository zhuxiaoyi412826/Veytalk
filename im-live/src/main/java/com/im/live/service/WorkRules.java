package com.im.live.service;

import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import com.im.live.entity.LiveImage;
import com.im.live.entity.LiveNovel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 三板块（图片 / 音乐 / 小说）写入前的字段兜底与现算规则。
 *
 * <p>抽成一份静态工具而不是抄进三个服务：用户端 {@code LiveXxxServiceImpl} 与后台端
 * {@code WorkAdminSpiImpl} 要落的是<b>同一批列、同一套口径</b>（标题留空给占位、分类留空落
 * 综合、小说字数服务端算）。两处各写一份，最先出的 bug 就是「后台新建的作品标题超长被库截断，
 * 而用户端发布是报错」这种两边行为不一致。
 *
 * <p>刻意不做成 Spring Bean：这里没有任何配置与外部依赖，纯函数更好测也更好读。
 */
public final class WorkRules {

    /** 标题列宽，与三张表的 {@code VARCHAR(60)} 对齐（也与 {@code im_live_video.title} 同长） */
    public static final int TITLE_MAX = 60;
    /** 分类列宽，与三张表的 {@code VARCHAR(32)} 对齐 */
    public static final int CATEGORY_MAX = 32;
    /** 摘要列宽，与 {@code im_live_novel.summary} 的 {@code VARCHAR(255)} 对齐 */
    public static final int SUMMARY_MAX = 255;
    /** 摘要缺省时从正文截取的长度 */
    public static final int DERIVED_SUMMARY_MAX = 80;
    /** 分类缺省值：保证列 NOT NULL，且信息流「全部」视图能统一回显 */
    public static final String DEFAULT_CATEGORY = "综合";

    private WorkRules() {
    }

    /**
     * 标题兜底：留空给「未命名 + 板块名」的占位，超宽截断到列宽。
     *
     * <p>截断而不是报错：标题是可有可无的装饰，为一行文案拒掉用户已经传完的几百 MB 图集不值得。
     *
     * @param boardName 板块中文名（图片 / 音乐 / 小说），拼进占位标题
     */
    public static String title(String raw, String boardName) {
        String value = TextUtil.isBlank(raw) ? "未命名" + boardName : raw.trim();
        return value.length() > TITLE_MAX ? value.substring(0, TITLE_MAX) : value;
    }

    /** 分类兜底：留空落 {@link #DEFAULT_CATEGORY}，超宽截断到列宽 */
    public static String category(String raw) {
        String value = TextUtil.isBlank(raw) ? DEFAULT_CATEGORY : raw.trim();
        return value.length() > CATEGORY_MAX ? value.substring(0, CATEGORY_MAX) : value;
    }

    /**
     * 字数：正文去掉所有空白后的字符数。
     *
     * <p>取「非空白字符」而不是「单词数」：中文小说没有空格可分，按空白切词永远算出 1。
     */
    public static int wordCount(String content) {
        if (TextUtil.isBlank(content)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            if (!Character.isWhitespace(content.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** 正文字节数（UTF-8）：{@code String.length()} 是 UTF-16 码元数，中文恰好差一半，别拿它当 size */
    public static long utf8Size(String content) {
        return content == null ? 0L : content.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 摘要兜底：作者填了就截到列宽，没填就从正文剥掉 Markdown 记号后截前 {@link #DERIVED_SUMMARY_MAX} 字。
     *
     * <p>剥记号是因为摘要要以纯文本显示在卡片上：把 {@code ## 标题} 或一整段图片语法原样贴出来，
     * 卡片第一行就被这些字符占满。算法与 {@code ArticleServiceImpl} 同源（三板块与文章板块的
     * 摘要观感必须一致），差异只在截取长度。
     */
    public static String summary(String rawSummary, String content) {
        if (TextUtil.isNotBlank(rawSummary)) {
            return truncate(rawSummary.trim(), SUMMARY_MAX);
        }
        if (TextUtil.isBlank(content)) {
            return "";
        }
        String plain = content
                .replaceAll("`{1,3}", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")    // 图片整段去掉
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")  // 链接只留文字
                .replaceAll("^\\s{0,3}#{1,6}\\s*", "")       // 标题记号
                .replaceAll("[*_-]{1,}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return truncate(plain, DERIVED_SUMMARY_MAX);
    }

    /** 通用截断：null 归空串，超长切到 max（不加省略号——列宽就是给定的长度） */
    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * 图集 ID 规范化：去 null、校验 {@value LiveImage#MIN_IMAGES}~{@value LiveImage#MAX_IMAGES} 张，
     * <b>顺序即展示顺序</b>（调用方已按用户选的顺序传）。
     *
     * <p>不去重：两张相同的图是用户真选了两张，去掉反而让「N 图」角标跟他数的对不上。
     * 越界直接 {@code BAD_REQUEST} 而不是悄悄少留一张或截断——少一张就是数据丢失。
     *
     * <p>放在这里而不是留在 {@code LiveImageServiceImpl} 里：用户端发布与后台代建走的是同一张表、
     * 同一对 NOT NULL 列，两处的张数口径必须一致，否则「后台能存 12 张、前台报 1~9 张」会变成
     * 一条很难复现的工单。
     */
    public static List<Long> albumIds(List<Long> imageIds) {
        List<Long> ids = new ArrayList<>();
        if (imageIds != null) {
            for (Long fileId : imageIds) {
                if (fileId != null) {
                    ids.add(fileId);
                }
            }
        }
        if (ids.size() < LiveImage.MIN_IMAGES || ids.size() > LiveImage.MAX_IMAGES) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "一次要发 " + LiveImage.MIN_IMAGES + "~" + LiveImage.MAX_IMAGES + " 张图，当前 " + ids.size() + " 张");
        }
        return ids;
    }

    /** 把图集 ID 拼成 {@code image_ids} 列的逗号并列串（雪花 ID 不含逗号，反解无歧义） */
    public static String albumCsv(List<Long> ids) {
        StringBuilder joined = new StringBuilder();
        for (Long id : ids) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        return joined.toString();
    }

    /**
     * 小说正文校验：非空 + 上限 {@value LiveNovel#CONTENT_MAX} 字符，返回 trim 后的正文。
     *
     * <p>超限<b>拒绝并提示分篇，绝不截断</b>：截断等于吃掉作者的稿子而他不会察觉。
     * 用户端发布与后台编辑共用本方法，理由同 {@link #albumIds(List)}。
     */
    public static String content(String raw) {
        BusinessException.throwIf(TextUtil.isBlank(raw), ResultCode.BAD_REQUEST, "正文不能为空");
        String value = raw.trim();
        if (value.length() > LiveNovel.CONTENT_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "正文最多 " + LiveNovel.CONTENT_MAX + " 字，超长请分篇发布");
        }
        return value;
    }
}
