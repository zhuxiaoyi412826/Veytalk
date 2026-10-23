package com.im.user.dto.po;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 红包未退余额投影：{@code WalletTransactionMapper.selectExpirablePackets} 的行形状，只给退款任务用。
 *
 * <p>{@code residual} 是同一个 {@code ref_id}（即 packetId）下 {@code packet} 与 {@code refund} 两类流水
 * 求和后的<b>负数</b>余额（发出去多少、被领走多少、已经退过多少都已经在求和里抵掉了），
 * 取反就是应该退给发送人的积分。之所以由账本倒推而不是读 Redis：
 * 领取态是易失的，钱必须是确定的——Redis 整个丢掉的后果只该是「红包领不了」，不该是「钱回不来」。
 */
@Data
public class PacketResidualPO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 红包 ID（= 流水的 ref_id） */
    private Long packetId;

    /** 发送人用户 ID，即那条负额 {@code packet} 流水的归属 */
    private Long senderId;

    /** 未退余额（负数，取反即为应退积分） */
    private Long residual;
}
