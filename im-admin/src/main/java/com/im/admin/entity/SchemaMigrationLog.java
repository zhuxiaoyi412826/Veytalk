package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 版本操作流水行，对应 {@code im_schema_migration_log}（一次 apply/rollback 一行、只 INSERT）。
 *
 * <p>失败也记行：半途炸掉的脚本正是最需要留痕的，statement_count 告诉人"前几条已经落库了"。
 * 后台「SQL 版本管理」页的时间线就是本表倒序。
 */
@Data
@TableName("im_schema_migration_log")
public class SchemaMigrationLog {

    /** 动作：应用 */
    public static final String ACTION_APPLY = "APPLY";
    /** 动作：回滚 */
    public static final String ACTION_ROLLBACK = "ROLLBACK";
    /** 动作：手工登记基线（引导脚本写入） */
    public static final String ACTION_MARK = "MARK";

    /** 结果：成功 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 结果：失败 */
    public static final String RESULT_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String version;

    /** APPLY / ROLLBACK / MARK */
    private String action;

    /** SUCCESS / FAILED */
    private String result;

    /** 成功执行的语句条数（FAILED 时为挂掉前跑掉的条数） */
    private Integer statementCount;

    private Long durationMs;

    /** 失败原因或备注，写入前截到 1000 字符 */
    private String message;

    private Long operatorId;

    private String operatorName;

    /** 发生时刻由库默认值维护，实体只读 */
    private LocalDateTime createTime;
}
