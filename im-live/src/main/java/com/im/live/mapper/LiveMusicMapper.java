package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.entity.LiveMusic;

/**
 * 音乐板块 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>不写自定义 SQL，理由同 {@link LiveImageMapper}。
 */
public interface LiveMusicMapper extends BaseMapper<LiveMusic> {
}
