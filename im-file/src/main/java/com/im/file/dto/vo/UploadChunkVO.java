package com.im.file.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单个分片上传后的回执。
 *
 * <p>把「服务端当前已收到的全部分片下标」一并回给前端，而不是只回一个成功标志：
 * 前端可以据此纠正本地进度、并在并发上传时避免重复投递同一分片，
 * 断线重连后也能凭这份列表决定还差哪些。
 */
@Data
@Builder
@Schema(description = "分片上传回执")
public class UploadChunkVO {

    @Schema(description = "本次成功落盘的分片下标")
    private Integer chunkIndex;

    @Schema(description = "服务端目前已收到的全部分片下标（升序）")
    private List<Integer> uploadedChunks;
}
