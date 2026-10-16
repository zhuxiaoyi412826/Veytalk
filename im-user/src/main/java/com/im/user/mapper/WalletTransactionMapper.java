package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.WalletTransaction;
import org.apache.ibatis.annotations.Mapper;

/**
 * 钱包账本 Mapper。
 *
 * <p>账本只增不改，本期只做插入；「积分明细」分页查询留给后续（可在此加 {@code selectByUser} 游标翻页，
 * 命中 {@code idx_user_time} 索引）。
 */
@Mapper
public interface WalletTransactionMapper extends BaseMapper<WalletTransaction> {
}
