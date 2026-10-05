package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 开播请求。
 *
 * <p>只收「房间信息」，不收任何媒体参数：分辨率与码率虽然也在这里声明，
 * 但它们只是给列表展示与带宽估算用的<b>标签</b>，真正生效的值写在推流端
 * ffmpeg 的命令行里。服务端不去校验两者是否一致——校验了也拦不住谎报，
 * 而谎报的后果（列表上写着 1080p 实际 720p）只影响展示，不值得为它加一道门禁。
 */
@Data
@Schema(description = "开播请求")
public class LiveStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "房间标题", example = "周五晚：Spring Boot 4 新特性实战")
    @NotBlank(message = "房间标题不能为空")
    @Size(max = 60, message = "房间标题最多 60 字")
    private String title;

    @Schema(description = "封面图访问地址，可空")
    @Size(max = 512, message = "封面地址过长")
    private String cover;

    @Schema(description = "房间公告，进房时下发一次", example = "欢迎提问，弹幕请文明")
    @Size(max = 512, message = "公告最多 512 字")
    private String notice;

    @Schema(description = "推流源：screen 屏幕分享 / camera 摄像头", example = "screen")
    private String sourceType;

    @Schema(description = "分辨率档位标签", example = "720p")
    private String resolution;

    @Schema(description = "视频码率（kbps）", example = "2500")
    private Integer bitrateKbps;
}
