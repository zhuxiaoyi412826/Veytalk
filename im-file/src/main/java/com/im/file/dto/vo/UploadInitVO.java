package com.im.file.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分片上传初始化结果。
 *
 * <p>有两种截然不同的返回形态，前端据 {@code uploaded} 分流：
 * <ul>
 *   <li>{@code uploaded=true}：整文件秒传命中，{@code file} 已是一条可用的文件记录，
 *       前端不必再上传任何字节，直接进入发送流程。</li>
 *   <li>{@code uploaded=false}：需要走分片上传，{@code uploadId} 是本次会话的凭据，
 *       {@code chunkSize} 是服务端权威分片大小（前端必须按它切片），
 *       {@code uploadedChunks} 是断点续传时服务端已收到的分片下标，前端跳过这些即可。</li>
 * </ul>
 */
@Data
@Builder
@Schema(description = "分片上传初始化结果")
public class UploadInitVO {

    @Schema(description = "是否已秒传完成：true 时无需再上传任何字节")
    private Boolean uploaded;

    @Schema(description = "上传会话 ID，uploaded=false 时用于后续分片上传与合并")
    private String uploadId;

    @Schema(description = "服务端权威分片大小（字节），前端必须按它切片")
    private Long chunkSize;

    @Schema(description = "按 chunkSize 切出的分片总数")
    private Integer totalChunks;

    @Schema(description = "服务端已收到的分片下标，断点续传时前端跳过这些")
    private List<Integer> uploadedChunks;

    @Schema(description = "秒传命中时直接返回的文件信息，uploaded=true 时非空")
    private FileVO file;
}
