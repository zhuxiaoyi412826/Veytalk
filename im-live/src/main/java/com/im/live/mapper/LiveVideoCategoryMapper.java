package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.entity.LiveVideoCategory;

/**
 * 视频板块二级分类字典 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>不写自定义 SQL：全是「按状态过滤 + sort 升序」「按 ID 取一行」「同名查重」这类单表条件查询，
 * 服务层用 LambdaQueryWrapper 拼即可（与 {@link LiveVideoMapper} 同口径）。
 */
public interface LiveVideoCategoryMapper extends BaseMapper<LiveVideoCategory> {
}
