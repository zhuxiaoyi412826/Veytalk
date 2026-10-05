package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.entity.LiveRoom;

/**
 * 直播房间 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>不写自定义 SQL：本表的查询全是「按主播查进行中」「按状态分页」这类单表条件查询，
 * 在服务层用 LambdaQueryWrapper 拼即可；多一层 XML 只会让「改了字段忘了改 SQL」
 * 这类漂移多一个发生的地方。
 */
public interface LiveRoomMapper extends BaseMapper<LiveRoom> {
}
