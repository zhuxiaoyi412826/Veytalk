package com.im.admin.dto.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 一条 SQL 版本操作流水（页面时间线的数据行）：谁、什么时候、对哪个版本做了什么、结果如何。
 */
@Data
@Builder
public class SchemaLogVO {

    private String version;

    /** APPLY / ROLLBACK / MARK */
    private String action;

    /** SUCCESS / FAILED */
    private String result;

    private Integer statementCount;

    private Long durationMs;

    private String message;

    private String operatorName;

    /** yyyy-MM-dd HH:mm:ss */
    private String createTime;
}
