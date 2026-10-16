package com.im.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.live.entity.GiftRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 送礼流水 Mapper。
 *
 * <p>本期只需插入（送礼即写一条账本性质流水）；历史回看/收礼榜聚合留给后续，
 * 已建 {@code idx_room_time}/{@code idx_receiver_time} 两个索引为其兜底。
 */
@Mapper
public interface GiftRecordMapper extends BaseMapper<GiftRecord> {
}
