package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.entity.LiveNovel;

/**
 * 小说板块 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>不写自定义 SQL，但<b>用本 Mapper 做分页查询时必须显式排除 {@code content}</b>
 * （LONGTEXT）：MyBatis-Plus 默认 {@code select * }，一屏 15 篇长文就能把列表接口拖成内存黑洞。
 * 约束写在服务层（{@code LiveNovelServiceImpl#page} 的 {@code select(...)} 列表），
 * 这里提醒一句是因为绕过服务层直接调本 Mapper 也能编译通过。
 */
public interface LiveNovelMapper extends BaseMapper<LiveNovel> {
}
