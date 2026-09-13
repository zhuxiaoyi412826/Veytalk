package com.im.file.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件视图对象。
 *
 * <p>{@code url} 与 {@code signedUrl} 的区别是前端最容易踩的坑：前者是长期有效的受控地址、
 * 可以安全地存进消息里，但直接放进 {@code <img src>} 会因为带不上登录头而 401；
 * 后者附带短时票据、可以直接渲染，但过期后就失效，绝不能落库。
 */
@Data
@Builder
@Schema(description = "文件信息")
public class FileVO {

    @Schema(description = "文件 ID")
    private Long fileId;

    @Schema(description = "上传者 ID")
    private Long uploaderId;

    @Schema(description = "业务类型：avatar / chat_image / chat_file / chat_voice")
    private String bizType;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "长期有效的受控访问地址，可落库，直接访问需要登录态")
    private String url;

    @Schema(description = "附带短时票据的访问地址，可直接用于 img src，过期后需重新获取")
    private String signedUrl;

    @Schema(description = "文件字节数")
    private Long size;

    @Schema(description = "可读的文件大小，例如 1.2 MB")
    private String sizeText;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "扩展名，不含点")
    private String ext;

    @Schema(description = "文件 MD5")
    private String md5;

    @Schema(description = "语音时长（秒），仅语音文件有值")
    private Integer duration;

    @Schema(description = "是否图片类，前端据此决定用图片气泡还是文件卡片渲染")
    private Boolean image;

    @Schema(description = "上传时间")
    private LocalDateTime createTime;
}
