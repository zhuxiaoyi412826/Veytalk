package com.im.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.ai.entity.AiGuard;

/**
 * AI 封禁状态 Mapper（AI 护栏 · 四期第六批）。主键即 userId，全部走 selectById / insert / updateById。
 */
public interface AiGuardMapper extends BaseMapper<AiGuard> {
}
