package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDate;

/**
 * 用户钱包实体，对应表 {@code im_user_wallet}，一行一个用户。
 *
 * <p>主键 {@code userId} 直接复用 {@code im_user.id}（{@link IdType#INPUT} 由业务写入，不走雪花）：
 * 钱包与用户是 1:1，用同一个 ID 既省一次映射查询，也让「用户不存在则钱包不存在」天然成立。
 *
 * <p>{@code balance} 恒非负——扣款走条件 {@code UPDATE ... WHERE balance >= ?}，把「够不够扣」交给
 * 数据库的行锁判定，而不是「先查后扣」的应用层判断，避免并发下把余额扣成负数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user_wallet")
public class UserWallet extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 积分/币余额，非负 */
    private Long balance;

    /** 累计充值到账 */
    private Long totalRecharged;

    /** 累计消费（送礼等） */
    private Long totalConsumed;

    /** 最近签到自然日，按天去重；未签到为 null */
    private LocalDate signLastDate;
}
