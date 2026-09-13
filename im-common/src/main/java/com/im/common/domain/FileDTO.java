package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件元数据，跨模块传输使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件元数据")
public class FileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "文件 ID")
    private Long fileId;

    @Schema(description = "上传者 ID")
    private Long uploaderId;

    @Schema(description = "业务类型：avatar / chat_image / chat_file / chat_voice")
    private String bizType;

    @Schema(description = "存储类型：minio / local")
    private String storageType;

    @Schema(description = "存储桶")
    private String bucket;

    @Schema(description = "对象键")
    private String objectKey;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "访问地址（受控下载地址或预签名地址）")
    private String url;

    @Schema(description = "缩略图地址，图片类文件有效")
    private String thumbnailUrl;

    @Schema(description = "文件字节数")
    private Long size;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "扩展名，不含点")
    private String ext;

    @Schema(description = "文件 MD5，用于秒传")
    private String md5;

    @Schema(description = "语音时长（秒），语音文件有效")
    private Integer duration;

    @Schema(description = "上传时间")
    private LocalDateTime createTime;
}
