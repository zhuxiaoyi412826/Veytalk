package com.im.admin.dto.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 「SQL 版本管理」页的整屏数据：脚本目录状态 + 台账/目录合并清单。
 *
 * <p>引导表没建（还没跑 sql/migration_schema_version.sql）时不抛 500，而是
 * {@code tableReady=false} + 空清单，页面显示"请先执行引导脚本"的提示条——
 * 这个功能自己就是管数据库变更的，第一次进不去页面反而是最典型的场景，必须能看清原因。
 */
@Data
@Builder
public class SchemaOverviewVO {

    /** 实际生效的脚本目录绝对路径；未找到时为配置值或探测起点说明 */
    private String dir;

    /** sql/versions 目录是否找到 */
    private Boolean dirFound;

    /** im_schema_migration 台账表是否已存在（引导脚本跑过没有） */
    private Boolean tableReady;

    /** 当前版本号最高、且状态为已应用的版本（LIFO 回滚的队头），全回滚/无台账时为 null */
    private String latestApplied;

    private List<SchemaVersionVO> items;
}
