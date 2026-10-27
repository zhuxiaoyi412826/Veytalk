package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台钱包屏顶部的几个数，由 im-user 经 {@code AdminWalletSpi#overview()} 给。
 *
 * <p>三个 SUM 是全表扫（{@code im_user_wallet} 一行一个用户，除主键外无索引）。
 * 这张表的行数与会话用户数同阶，且这一屏<b>不做自动轮询</b>（手点刷新），
 * 所以不为此专门加缓存：加了就要面对「后台调完余额，看板数字还停在旧值」这种更难解释的问题。
 * 真到了钱包行数量级失控那天，正确做法是给这一屏补一条物化的统计行，而不是在这里塞 Redis。
 *
 * <p>{@code walletEnabled} 是 {@code im.wallet.enabled} 的当前运行态。带上它是因为
 * 一个「所有数字都是 0」的页面有两种可能：功能没开，或者真没人用——
 * 少了这一位，管理员就得去系统配置页对照着看，两屏之间来回跳。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletOverviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 钱包功能当前是否开启（运行态，改了配置约 30 秒内被后台同步任务拨动） */
    private Boolean walletEnabled;

    /** 开过钱包的用户数（im_user_wallet 行数） */
    private Long accountCount;

    /** 全站余额合计 */
    private Long totalBalance;

    /** 全站累计充值合计 */
    private Long totalRecharged;

    /** 全站累计消费合计（口径见 {@code AdminWalletDTO} 的类注释） */
    private Long totalConsumed;

    /** 今日流水条数（自然日 0 点起） */
    private Long todayTxCount;

    /** 今日流水净额（进账为正、出账为负的代数和） */
    private Long todayTxAmount;

    /** 今日后台调整条数——这一列不为 0 就说明有人手工动过钱 */
    private Long todayAdjustCount;

    /** 生成时刻，用于页面上「数字截至几点」的提示 */
    private LocalDateTime generatedAt;
}
