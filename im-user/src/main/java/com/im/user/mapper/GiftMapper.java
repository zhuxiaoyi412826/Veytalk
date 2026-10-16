package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.Gift;
import org.apache.ibatis.annotations.Mapper;

/**
 * 礼物目录 Mapper。
 *
 * <p>目录查询用 {@code LambdaQueryWrapper}（{@code status=1} + 按 {@code sort} 升序）即可，
 * 逻辑删除条件由 MyBatis-Plus 自动追加，无需手写 SQL。
 */
@Mapper
public interface GiftMapper extends BaseMapper<Gift> {
}
