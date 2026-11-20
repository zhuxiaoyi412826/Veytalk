package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 版本台账行，对应 {@code im_schema_migration}（一行 = 一个版本号的当前态）。
 *
 * <p>不继承 AuditEntity：本表没有 deleted 列——版本台账是事实记录，不做逻辑删除。
 * 版本号唯一（uk_version），"回滚"是把 status 翻成 0 并留 rolled_back_at，行本身永远保留，
 * 否则回滚过的版本会像从没存在过一样从页面上消失。
 *
 * <p>create_time / update_time 由库默认值与 ON UPDATE 维护，实体只读不写。
 */
@Data
@TableName("im_schema_migration")
public class SchemaMigration {

    /** 状态：已应用 */
    public static final int STATUS_APPLIED = 1;
    /** 状态：已回滚 */
    public static final int STATUS_ROLLED_BACK = 0;
    /** 状态：应用失败（半截脚本，人工修补后重放） */
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 版本号（如 1.0.4），与 md/SQL变更文档.md 台账同一序列 */
    private String version;

    /** 向前脚本文件名；基线行写的是合集说明文本而不是文件 */
    private String scriptName;

    /** 回滚脚本文件名；NULL 表示不可一键回滚 */
    private String undoName;

    private String description;

    /** 向前脚本文件 SHA-256 小写；重算不一致 = 已应用的脚本后来被改过 */
    private String checksum;

    /** 1 已应用 / 0 已回滚 / 2 应用失败 */
    private Integer status;

    private Long operatorId;

    /** 执行人昵称快照（昵称后来改了不影响历史可读）；基线行为 SYSTEM */
    private String operatorName;

    private LocalDateTime appliedAt;

    private LocalDateTime rolledBackAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
