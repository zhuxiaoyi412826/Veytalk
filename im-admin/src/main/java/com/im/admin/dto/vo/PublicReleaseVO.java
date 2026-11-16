package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 官网下载页用的公开版本视图：每个平台至多一行（启用中的那行）。
 *
 * <p>只带匿名访客需要的字段，<b>不带</b> createBy / updateBy / fileId：
 * 操作人 ID 与内部文件主键对访客是无用信息，少发一个就少一个能被枚举的入口。
 *
 * <p>{@code downloadPath} 是相对路径（{@code /api/release/download/{id}}）：
 * 官网与后端可能同域（nginx 反代）也可能跨域（环境变量顶基址），拼不拼前缀由官网自己决定，
 * 服务端不猜部署拓扑。
 */
@Data
@Builder
@Schema(description = "客户端版本（官网公开视图）")
public class PublicReleaseVO implements Serializable {

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

    @Schema(description = "安装包展示名")
    private String fileName;

    @Schema(description = "安装包字节数")
    private Long fileSize;

    @Schema(description = "整文件 MD5，供用户校验下载完整性")
    private String md5;

    @Schema(description = "启用（更新）时间")
    private LocalDateTime updateTime;

    @Schema(description = "匿名下载地址（相对路径）")
    private String downloadPath;
}
