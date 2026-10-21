package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 日志尾读响应（三期）。
 *
 * <p>{@code lines} 顺序与文件一致（旧→新），前端渲染时不再倒序——日志按时间正读
 * 才是排障姿势；「最后一行在最下面」与 tail -f 的肌肉记忆保持一致。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日志尾读结果")
public class LogContentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "日志逻辑标识")
    private String key;

    @Schema(description = "实际读取的相对路径，仅展示")
    private String relPath;

    @Schema(description = "文件是否存在")
    private Boolean exists;

    @Schema(description = "本次返回的行（旧→新）")
    private List<String> lines;

    @Schema(description = "返回行数")
    private Integer returnedLines;

    @Schema(description = "是否因 tail 上限被截断（还有更早的内容没给）")
    private Boolean truncated;

    @Schema(description = "关键词过滤说明：过滤发生在尾读窗口内，不是全文件搜索")
    private String filterNote;
}
