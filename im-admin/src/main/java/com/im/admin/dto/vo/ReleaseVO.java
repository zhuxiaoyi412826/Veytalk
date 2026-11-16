package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台客户端版本行视图。
 *
 * <p>{@code platformName} / {@code statusName} 由服务端翻译好再下发：码表在
 * {@code ReleasePlatform} 与实体常量里，让前端各抄一份中文就会出现
 * 「后台叫安装版、官网叫安装包」的漂移。
 */
@Data
@Builder
@Schema(description = "客户端版本（后台视图）")
public class ReleaseVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "版本行 ID")
    private Long id;

    @Schema(description = "平台码")
    private String platform;

    @Schema(description = "平台中文名")
    private String platformName;

    @Schema(description = "版本号")
    private String version;

    @Schema(description = "更新说明")
    private String releaseNote;

    @Schema(description = "安装包文件 ID")
    private Long fileId;

    @Schema(description = "安装包展示名")
    private String fileName;

    @Schema(description = "安装包字节数")
    private Long fileSize;

    @Schema(description = "整文件 MD5")
    private String md5;

    @Schema(description = "状态：0 停用 1 启用")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusName;

    @Schema(description = "安装包字节当前是否仍在存储中：false 表示文件行/对象已缺失，启用前必须先换包")
    private Boolean filePresent;

    @Schema(description = "累计下载次数")
    private Long downloadCount;

    @Schema(description = "创建人（后台账号 ID）")
    private Long createBy;

    @Schema(description = "最后修改人（后台账号 ID）")
    private Long updateBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
