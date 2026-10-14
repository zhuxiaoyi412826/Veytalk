package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 视频板块信息流展示视图。
 *
 * <p>比实体多两处：上传者的昵称与头像（{@code im_live_video} 只存 uploader_id，展示时经
 * {@code UserQuerySpi} 补全，避免前端为每张卡片单独查用户）；以及 {@code mine} —— 由服务端
 * 判定当前登录用户是否上传者，前端据此决定要不要给「删除」按钮，不信任前端自己比对。
 *
 * <p>{@code fileUrl}/{@code coverUrl} 是长期受控地址，前端经 {@code mediaUrl} 取 blob 播放/渲染。
 */
@Data
@Schema(description = "视频板块信息")
public class LiveVideoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "视频 ID")
    private Long id;

    @Schema(description = "上传者用户 ID")
    private Long uploaderId;

    @Schema(description = "上传者昵称")
    private String uploaderName;

    @Schema(description = "上传者头像")
    private String uploaderAvatar;

    @Schema(description = "视频标题")
    private String title;

    @Schema(description = "视频文件 ID")
    private Long fileId;

    @Schema(description = "视频受控访问地址")
    private String fileUrl;

    @Schema(description = "封面图文件 ID，可空")
    private Long coverFileId;

    @Schema(description = "封面图受控访问地址，可空")
    private String coverUrl;

    @Schema(description = "时长（秒）")
    private Integer duration;

    @Schema(description = "视频宽（像素）")
    private Integer width;

    @Schema(description = "视频高（像素）")
    private Integer height;

    @Schema(description = "文件字节数")
    private Long size;

    @Schema(description = "扩展名")
    private String ext;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "播放次数")
    private Integer playCount;

    @Schema(description = "状态：1 正常 2 已下架")
    private Integer status;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;

    @Schema(description = "当前用户是否为上传者")
    private Boolean mine;
}
