package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.dto.po.PacketResidualPO;
import com.im.user.entity.WalletTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 钱包账本 Mapper。
 *
 * <p>账本只增不改，因此这里没有任何 {@code UPDATE}/{@code DELETE}：要么是插入一条流水，
 * 要么是聚合读。「积分明细」分页查询留给后续（可在此加 {@code selectByUser} 游标翻页，
 * 命中 {@code idx_user_time} 索引）。
 */
@Mapper
public interface WalletTransactionMapper extends BaseMapper<WalletTransaction> {

    /**
     * 扫出「已经到期、且还有没人领的余额」的红包，供退款任务把差额退回发送人。
     *
     * <p>判定完全在账本里做，不读 Redis 的红包态：一个红包发了多少（一条负额 {@code packet} 流水）、
     * 被领走多少（若干条正额 {@code packet} 流水）、退过多少（{@code refund} 流水），三者相加就是未退余额。
     * 于是这条 SQL 同时兼作退款幂等——退过的红包求和归零，下一拍自然不再被扫出来，
     * 不需要额外维护一张「已退款」表或标记位。
     *
     * <p>到期口径用 {@code create_time + hours} 而不是 Redis 里的 expireAt：后者是易失的，
     * 拿它当资金判定依据等于把退款寄托在缓存上。代价是运维改了
     * {@code im.wallet.packet-expire-hours} 会让存量在飞红包的到期时间跟着挪——
     * 它是运维旋钮，不是对账约定。
     *
     * <p>{@code LIMIT} 是必要的兜底：万一长时间没跑（服务停了一整天）积压会一次性涌出来，
     * 逐条入账比一个大事务稳妥得多，剩下的下一拍接着处理。
     *
     * @param hours 保留时长（小时），来自配置
     */
    @Select("""
            SELECT t.ref_id AS packetId,
                   MAX(CASE WHEN t.amount < 0 THEN t.user_id END) AS senderId,
                   SUM(t.amount) AS residual
            FROM im_wallet_transaction t
            WHERE t.type IN ('packet', 'refund')
              AND t.ref_id IS NOT NULL
              AND t.create_time < DATE_SUB(NOW(), INTERVAL #{hours} HOUR)
            GROUP BY t.ref_id
            HAVING SUM(t.amount) < 0
            LIMIT 200
            """)
    List<PacketResidualPO> selectExpirablePackets(@Param("hours") int hours);
}
