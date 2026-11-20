package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.SchemaMigration;

/**
 * SQL 版本台账 Mapper。整张表就是几十行上限（一个版本一行），全部走
 * {@code selectList} 一次取回内存合并，不需要任何自定义 SQL。
 */
public interface SchemaMigrationMapper extends BaseMapper<SchemaMigration> {
}
