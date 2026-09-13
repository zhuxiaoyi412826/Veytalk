package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件上传指令，跨模块调用 {@code FileStorageSpi#upload} 时使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件上传指令")
public class UploadCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "上传者 ID")
    private Long uploaderId;

    @Schema(description = "业务类型：avatar / chat_image / chat_file / chat_voice")
    private String bizType;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "文件字节数")
    private Long size;

    @Schema(description = "文件 MD5")
    private String md5;

    @Schema(description = "语音时长（秒），语音文件有效")
    private Integer duration;

    @Schema(description = "文件字节内容")
    private transient byte[] bytes;
}
