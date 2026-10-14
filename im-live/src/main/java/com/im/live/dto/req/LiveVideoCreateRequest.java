package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发布视频请求。
 *
 * <p>前端先用 {@code uploadFileSmart} 把视频（bizType=video）与可选封面（bizType=video_cover）
 * 传到 {@code im_file}，拿到返回的 {@code fileId} 后再调本接口建信息流记录。<b>只传 ID，不传地址</b>：
 * 受控访问地址 {@code /api/file/download/{id}} 由服务端按配置前缀拼出，避免客户端伪造一条指向
 * 任意文件的地址；文件真实可达性在下载时按 bizType 公开策略再校验一次。
 *
 * <p>时长/宽高/大小/扩展名/MIME 由客户端读取元数据后随请求带上，服务端只存不校验（与开播的
 * 分辨率/码率同理：校验了也拦不住谎报，而谎报只影响列表展示，不值得为它加门禁）。
 */
@Data
@Schema(description = "发布视频请求")
public class LiveVideoCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "视频标题", example = "周末剪辑：山间徒步")
    @Size(max = 60, message = "视频标题最多 60 字")
    private String title;

    @Schema(description = "视频文件 ID（已通过 bizType=video 上传）")
    @NotNull(message = "视频文件 ID 不能为空")
    private Long fileId;

    @Schema(description = "封面图文件 ID（已通过 bizType=video_cover 上传），可空——为空时前端退化为占位图")
    private Long coverFileId;

    @Schema(description = "时长（秒），可空")
    private Integer duration;

    @Schema(description = "视频宽（像素），可空")
    private Integer width;

    @Schema(description = "视频高（像素），可空")
    private Integer height;

    @Schema(description = "文件字节数")
    @NotNull(message = "文件大小不能为空")
    private Long size;

    @Schema(description = "扩展名，不含点", example = "mp4")
    private String ext;

    @Schema(description = "MIME 类型", example = "video/mp4")
    private String contentType;
}
