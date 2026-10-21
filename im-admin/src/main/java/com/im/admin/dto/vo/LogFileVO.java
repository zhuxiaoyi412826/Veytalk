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
 * 日志管理页的一条「日志文件」记录（三期）。
 *
 * <p>前端只认 {@code key}（如 {@code error}），永远不传路径：key 到真实文件的映射表
 * 写死在 {@code AdminLogService} 里，这是路径穿越防线的第一层（也是主要一层）——
 * 用户连「路径」这个概念都接触不到，自然无处穿越。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日志文件条目")
public class LogFileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "逻辑标识，读内容接口唯一可传的参数")
    private String key;

    @Schema(description = "中文名，下拉框显示用")
    private String label;

    @Schema(description = "相对日志根目录的路径，仅展示用（如 SQL/slow-sql.log）")
    private String relPath;

    @Schema(description = "文件是否存在（还没产生过的档为 false，不是错误）")
    private Boolean exists;

    @Schema(description = "字节数，不存在为 null")
    private Long sizeBytes;

    @Schema(description = "最后修改时间，不存在为 null")
    private LocalDateTime lastModified;

    @Schema(description = "是否敏感档（慢 SQL/审核/日志原文可能含个人信息，前端据此提示）")
    private Boolean sensitive;
}
