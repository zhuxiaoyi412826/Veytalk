package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.entity.Article;

/**
 * 文章板块 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>与 {@code LiveVideoMapper} 同理不写自定义 SQL：本表查询全是「按状态分页」「按作者分页」
 * 「按 ID 取一行」这类单表条件查询，服务层用 LambdaQueryWrapper 拼即可。
 */
public interface ArticleMapper extends BaseMapper<Article> {
}
