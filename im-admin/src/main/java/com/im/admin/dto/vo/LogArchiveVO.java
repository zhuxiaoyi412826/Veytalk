package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一个日志档的归档文件（五期·路线 1）。
 *
 * <p>logback 跨天把活动文件滚成 {@code name-yyyy-MM-dd.N.log}，本页把它们列出来供选；
 * 前端回传的是 {@code date + index} 这两个<b>受格式校验的结构化值</b>而不是文件名本身，
 * 路径仍由服务端按白名单映射表拼装——与「不传路径」这条防线保持一致。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日志归档文件")
public class LogArchiveVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "所属日志档 key")
    private String key;

    @Schema(description = "归档日期，形如 2026-10-14")
    private String date;

    @Schema(description = "同一天内的分段序号（单文件超 maxFileSize 才会出现 >0）")
    private Integer index;

    @Schema(description = "文件名，仅展示，不作为回传参数")
    private String fileName;

    @Schema(description = "文件字节数")
    private Long sizeBytes;

    @Schema(description = "最后修改时间（即滚存时间，之后不再变化）")
    private LocalDateTime lastModified;
}
