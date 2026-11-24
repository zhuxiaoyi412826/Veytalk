package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发布动态时带上的一张图 / 一个视频：<b>只传已上传好的 {@code fileId}</b>，不传 URL。
 *
 * <p>地址由服务端按 {@code fileId} 反查后现拼（{@code FileStorageSpi#accessUrl}），
 * 前端传来的同类字段一律不信：动态是能被好友与配对对象看到的位置，放开 URL
 * 等于允许往信息流里挂任意外链，而外链既躲过审核也躲过「已删除」这件事。
 *
 * <p>{@code type} 也不由服务端从文件扩展名猜：一个 {@code .mp4} 可以当图位放，
 * 但九宫格画不出它，前端知道自己放进哪一格。服务端要校验的是「类型与 {@code bizType} 对得上」，
 * 那才是能防住伪造的那一条。
 *
 * <p>宽高与时长是客户端上报的展示元数据（用于版式占位，避免加载时页面抖动），
 * 服务端不解码媒体：那需要在 im-friend 里引一份解码依赖，而算出来的值除了排版没有别的用途。
 */
@Data
@Schema(description = "动态附件项")
public class MomentMediaItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "im_file.id，必须是本人上传且 bizType 与 type 对应的文件", example = "194823748239849472")
    @NotNull(message = "fileId 不能为空")
    private Long fileId;

    @Schema(description = "类型：1 图片 2 视频", example = "1")
    @NotNull(message = "type 不能为空")
    @Min(value = 1, message = "type 只能是 1 图片或 2 视频")
    @Max(value = 2, message = "type 只能是 1 图片或 2 视频")
    private Integer type;

    @Schema(description = "像素宽，仅用于前端版式占位", example = "1080")
    @Min(value = 0, message = "宽度不能为负")
    @Max(value = 20000, message = "宽度超出合理范围")
    private Integer width;

    @Schema(description = "像素高", example = "1440")
    @Min(value = 0, message = "高度不能为负")
    @Max(value = 20000, message = "高度超出合理范围")
    private Integer height;

    @Schema(description = "视频时长（秒），图片不传", example = "15")
    @Min(value = 0, message = "时长不能为负")
    @Max(value = 86400, message = "时长超出合理范围")
    private Integer duration;
}
