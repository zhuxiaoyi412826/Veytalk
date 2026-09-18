package com.im.file.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分片上传初始化入参。
 *
 * <p>前端在真正上传任何字节之前先调 init：把整文件 MD5 报上来，服务端据此判定能否秒传
 * （已有相同 MD5 的对象就直接为当前用户建一条记录，跳过上传），不能秒传才下发分片大小、
 * 建立上传会话，并把「已经收到哪些分片」回给前端用于断点续传。
 *
 * <p>{@code md5} 与 {@code size} 都来自客户端，不可全信：合并阶段会把临时分片重新读一遍、
 * 重算 MD5 与字节数并与这里声明的值逐一核对，对不上直接拒绝，谎报内容拼不出一个合法文件。
 */
@Data
@Schema(description = "分片上传初始化入参")
public class UploadInitReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "整文件 MD5（32 位十六进制），秒传与合并校验的依据",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "5d41402abc4b2a76b9719d911017c592")
    @NotBlank(message = "md5 不能为空")
    private String md5;

    @Schema(description = "整文件字节数", requiredMode = Schema.RequiredMode.REQUIRED, example = "10485760")
    @NotNull(message = "文件大小不能为空")
    @Positive(message = "文件大小必须为正数")
    private Long size;

    @Schema(description = "原始文件名，用于判定扩展名白名单与下载时的展示名",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "demo.mp4")
    @NotBlank(message = "文件名不能为空")
    private String originalName;

    @Schema(description = "业务类型：chat_image / chat_file / chat_voice，缺省按 chat_file 处理",
            example = "chat_file")
    private String bizType;

    @Schema(description = "语音时长（秒），仅语音文件需要", example = "12")
    private Integer duration;
}
