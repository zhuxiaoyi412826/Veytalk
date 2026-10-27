package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台钱包视角下的一个账户，由 im-user 经 {@code AdminWalletSpi} 提供。
 *
 * <p>它同时充当「台账里的一行」和「单人档案」：档案时 {@code recentTransactions}
 * 才有值，列表行为 null。不拆成两个类是沿用直播与远程控制后台的做法——
 * 两个形状的字段九成重合，拆开后每次加列都要改两处，迟早一处忘加。
 *
 * <p>三个金额都是<b>积分数</b>（{@code im_user_wallet} 里是 BIGINT），不是钱：
 * 平台没有法币余额这一说，充值走 {@code PaymentSpi} 换成积分，所以这里不带币种字段。
 * 也不折算成人民币——汇率是运营口径，写进后端就要跟着改代码。
 *
 * <p>{@code totalConsumed} 的口径值得注意：它<b>只计送礼等平台消费</b>，
 * 转账与发红包走 {@code debitBalance} 不动这一列（同一批积分只是从我的行挪到对方的行），
 * 所以「充值 − 消费 ≠ 余额」不是对不上账，而是设计上就少了转出的那部分。
 * 要看真实收支得翻 {@code AdminWalletTxDTO} 流水。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID（也是 {@code im_user_wallet} 的主键） */
    private Long userId;

    /** 昵称（im-user 查不到资料时给「已注销用户」占位） */
    private String nickname;

    /** 当前余额（积分） */
    private Long balance;

    /** 累计充值到账（只计 type=recharge 那一路） */
    private Long totalRecharged;

    /** 累计消费（只计送礼等平台扣款，不含转账/红包转出） */
    private Long totalConsumed;

    /** 最近一次签到的自然日，从未签到为 null */
    private LocalDate signLastDate;

    /** 钱包行创建时间 */
    private LocalDateTime createTime;

    /** 钱包行最后变动时间（余额条件更新时由 SQL 的 NOW() 拨动） */
    private LocalDateTime updateTime;

    /**
     * 最近若干条账本流水，只在单人档案里给（列表行为 null）。
     *
     * <p>条数由实现方夹死（取 10 条）：这一列是为了让「这个人的余额为什么变成这样」
     * 在第一屏就能看出端倪，而不是把整张流水表塞进档案响应里。
     */
    private List<AdminWalletTxDTO> recentTransactions;
}
