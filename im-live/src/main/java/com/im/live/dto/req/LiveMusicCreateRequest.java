package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发布 / 编辑音乐请求（{@code POST} 与 {@code PUT} 共用一个形状，语义为整条替换）。
 *
 * <p>形状与 {@link LiveVideoCreateRequest} 几乎一致（一个音频本体 + 一张可选封面），差别只在
 * 没有 width/height：音频没有画面尺寸，硬留两列只会让人以为能用。
 *
 * <p>时长/大小/扩展名/MIME 由客户端读元数据后随请求带上，服务端只存不校验（与视频同口径：
 * 校验了也拦不住谎报，而谎报只影响列表上的一行文案）。{@code coverFileId} 传 {@code null}
 * 即清空封面——这正是 PUT 不复用「null 表示不改」局部更新语义的原因。
 */
@Data
@Schema(description = "发布/编辑音乐请求")
public class LiveMusicCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "音频文件 ID（已通过 bizType=music 上传）")
    @NotNull(message = "音频文件 ID 不能为空")
    private Long fileId;

    @Schema(description = "封面图文件 ID（已通过 bizType=music_cover 上传），可空——为空时前端退化为占位图")
    private Long coverFileId;

    @Schema(description = "作品标题", example = "雨夜即兴")
    @Size(max = 60, message = "标题最多 60 字")
    private String title;

    @Schema(description = "分类标签（音乐板块二级分类），可空——空则服务端落 综合", example = "纯音乐")
    @Size(max = 32, message = "分类最多 32 字")
    private String category;

    @Schema(description = "时长（秒），可空")
    private Integer duration;

    @Schema(description = "文件字节数")
    @NotNull(message = "文件大小不能为空")
    private Long size;

    @Schema(description = "扩展名，不含点", example = "mp3")
    private String ext;

    @Schema(description = "MIME 类型", example = "audio/mpeg")
    private String contentType;
}
