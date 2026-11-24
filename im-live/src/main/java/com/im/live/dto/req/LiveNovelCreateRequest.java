package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发布 / 编辑小说请求（{@code POST} 与 {@code PUT} 共用一个形状，语义为整条替换）。
 *
 * <p>正文是客户端唯一能决定内容的字段，所以<b>只有它是必填</b>；字数与字节数一律服务端现算
 * （{@code LiveNovelServiceImpl} 里的 {@code wordCountOf} / {@code sizeOf}），摘要留空则由服务端
 * 从正文截取——这三个数都会显示在列表卡片上，让前端报就等于把版面交给最不可信的一方。
 *
 * <p>正文上限 20 万字符，超限<b>拒绝而不是截断</b>：悄悄截掉等于吃掉作者的稿子，
 * 那比弹一个错误严重得多。真要把长篇发上来就该分篇，这是板块形态决定的，不是这里能绕的。
 *
 * <p>与文章板块的区别：这里没有「存草稿 / 提交审核」，落库即 {@code status=1} 可见。
 */
@Data
@Schema(description = "发布/编辑小说请求")
public class LiveNovelCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Markdown 正文")
    @NotBlank(message = "正文不能为空")
    private String content;

    @Schema(description = "作品标题", example = "长夜将至")
    @Size(max = 60, message = "标题最多 60 字")
    private String title;

    @Schema(description = "分类标签（小说板块二级分类），可空——空则服务端落 综合", example = "科幻")
    @Size(max = 32, message = "分类最多 32 字")
    private String category;

    @Schema(description = "列表摘要，可空——空则服务端从正文截取前 80 字")
    @Size(max = 255, message = "摘要最多 255 字")
    private String summary;

    @Schema(description = "封面图文件 ID（已通过 bizType=novel_cover 上传），可空——为空时前端退化为占位图")
    private Long coverFileId;
}
