package com.im.common.domain;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.constant.ImConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询入参基类。
 */
@Data
@Schema(description = "分页查询参数")
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1", defaultValue = "1")
    private Long current = ImConstants.DEFAULT_PAGE_CURRENT;

    @Schema(description = "每页条数，最大 100", example = "20", defaultValue = "20")
    private Long size = ImConstants.DEFAULT_PAGE_SIZE;

    @Schema(description = "搜索关键字，可选")
    private String keyword;

    /**
     * 转换为 MyBatis-Plus 分页对象，同时对页码与页大小做边界收敛。
     */
    public <T> Page<T> toPage() {
        long safeCurrent = (current == null || current < 1) ? ImConstants.DEFAULT_PAGE_CURRENT : current;
        long safeSize = (size == null || size < 1) ? ImConstants.DEFAULT_PAGE_SIZE : size;
        if (safeSize > ImConstants.MAX_PAGE_SIZE) {
            safeSize = ImConstants.MAX_PAGE_SIZE;
        }
        return Page.of(safeCurrent, safeSize);
    }

    public long safeCurrent() {
        return (current == null || current < 1) ? ImConstants.DEFAULT_PAGE_CURRENT : current;
    }

    public long safeSize() {
        long safeSize = (size == null || size < 1) ? ImConstants.DEFAULT_PAGE_SIZE : size;
        return Math.min(safeSize, ImConstants.MAX_PAGE_SIZE);
    }
}
