package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 新增一张照片墙照片：只传已上传好的 {@code fileId}，不传 URL。
 *
 * <p>地址、字节数都由服务端按 {@code fileId} 反查后重新生成，前端传来的同类字段一律不信：
 * 照片墙是能被陌生人看到的公开位，放开 URL 等于允许往别人的资料卡里挂任意外链。
 *
 * <p>宽高是<b>客户端上报</b>的展示元数据（用于版式占位，避免图片加载时页面抖动），
 * 服务端不解析图片像素：那需要在 im-user 里引一份解码依赖，而它算出来的值除了排版没有别的用途。
 */
@Data
@Schema(description = "照片墙新增照片请求")
public class PhotoAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "im_file.id，必须是本人上传且 bizType=dating_photo 的文件", example = "194823748239849472")
    @NotNull(message = "fileId 不能为空")
    private Long fileId;

    @Schema(description = "客户端机器审核是否真实跑过且未命中违规：true 直接落「通过」，"
            + "false 或不传落「待审」——模型降级时不能把没检查过的图当已检查的放出去", example = "true")
    private Boolean machineChecked;

    @Schema(description = "像素宽，仅用于前端版式占位", example = "1080")
    @Min(value = 0, message = "宽度不能为负")
    @Max(value = 20000, message = "宽度超出合理范围")
    private Integer width;

    @Schema(description = "像素高", example = "1440")
    @Min(value = 0, message = "高度不能为负")
    @Max(value = 20000, message = "高度超出合理范围")
    private Integer height;
}
