package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.SchemaMigrationLog;

/**
 * SQL 版本操作流水 Mapper（只 INSERT + 倒序翻页读，永不更新）。
 */
public interface SchemaMigrationLogMapper extends BaseMapper<SchemaMigrationLog> {
}
