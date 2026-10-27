package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.ConfigItem;

/**
 * 运行期配置 Mapper。
 *
 * <p>整张表只有几十行（一个配置项最多一行，没改过的项根本没有行），所以这里
 * 一条自定义 SQL 都不需要：全量 {@code selectList} 一次取回来在内存里与目录合并，
 * 比按 key 逐条查要少十几次往返，而代价是一次读十几行。
 */
public interface ConfigItemMapper extends BaseMapper<ConfigItem> {
}
