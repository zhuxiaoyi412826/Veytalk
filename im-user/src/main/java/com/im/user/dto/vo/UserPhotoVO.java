package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 照片墙的一张照片。
 *
 * <p>★ 与审核状态相关的三个字段（{@link #fileId}/{@link #auditStatus}/{@link #rejectReason}）
 * <b>只在本人视角返回</b>：{@code dating_photo} 的下载地址是公开的，一旦被驳回那张的
 * {@code fileId} 或地址漏给外人，就等于替用户把这张图发出去了——可见性只能靠「不返回这一行」收口。
 *
 * <p>外人视角能拿到的就是 {@link #url} + 版式信息，连「这个人有几张待审」都不该看出来。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "照片墙照片")
public class UserPhotoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "照片 ID")
    private Long id;

    @Schema(description = "图片文件 ID（im_file.id）；仅本人视角返回")
    private Long fileId;

    @Schema(description = "图片地址（服务端按 fileId 现拼）")
    private String url;

    @Schema(description = "展示顺序，从 0 开始")
    private Integer sort;

    @Schema(description = "是否主封面：1 是 0 否；仅本人视角返回")
    private Integer isMain;

    @Schema(description = "审核状态：0 待审 1 通过 2 驳回；仅本人视角返回")
    private Integer auditStatus;

    @Schema(description = "审核状态文案，供前端角标；仅本人视角返回")
    private String auditStatusDesc;

    @Schema(description = "驳回原因，只有被驳回那张有；仅本人视角返回")
    private String rejectReason;

    @Schema(description = "像素宽，用于版式占位")
    private Integer width;

    @Schema(description = "像素高")
    private Integer height;

    @Schema(description = "字节数，服务端按文件元数据填；仅本人视角返回")
    private Long size;

    @Schema(description = "上传时间；仅本人视角返回")
    private LocalDateTime createTime;
}
