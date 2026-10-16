package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 钱包账本流水，对应表 {@code im_wallet_transaction}，只增不改，供对账与「积分明细」页。
 *
 * <p>继承 {@link AuditEntity} 而非 {@code BaseEntity}：账本是流水表，一笔流水不该被逻辑删除后
 * 留下「删过的记录」这种既污染对账又占唯一空间的中间态，因此表里没有 {@code deleted} 列。
 *
 * <p>{@code amount} 有符号（支出为负），{@code balanceAfter} 是这笔交易落库后的余额快照，
 * 让明细页不必从最早一笔累加就能显示「当时余额」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_wallet_transaction")
public class WalletTransaction extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 钱包归属用户 ID */
    private Long userId;

    /** 类型：recharge/gift/checkin/adjust，见 {@code ImConstants.WALLET_TX_*} */
    private String type;

    /** 变动额（有符号，支出为负） */
    private Long amount;

    /** 交易后余额快照 */
    private Long balanceAfter;

    /** 关联业务 ID（如送礼记录 ID / 房间 ID），可空 */
    private Long refId;

    /** 备注 */
    private String remark;
}
