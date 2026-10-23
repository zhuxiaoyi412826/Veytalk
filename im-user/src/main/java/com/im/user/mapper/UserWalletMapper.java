package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.UserWallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 钱包 Mapper。
 *
 * <p>余额变动一律用条件 {@code UPDATE} 而不是「先 SELECT 再写回」：{@code WHERE balance >= #{amount}}
 * 让「够不够扣」这件事在数据库行锁的原子判定里完成，两个并发请求同时扣同一笔余额时不会出现超扣。
 * 影响行数返回 0 即代表这次没扣动（余额不足或行不存在），业务层据此抛 {@code WALLET_BALANCE_NOT_ENOUGH}。
 *
 * <p>这些是手写 SQL，MyBatis-Plus 的逻辑删除拦截不会自动给 {@code WHERE} 补 {@code deleted = 0}，
 * 因此每条都显式带上，别指望框架兜底。
 */
@Mapper
public interface UserWalletMapper extends BaseMapper<UserWallet> {

    /**
     * 惰性建行李：用户第一次触达钱包（充值/送礼/签到）时补一行 0 余额。
     * 主键 {@code user_id} 冲突即 {@code INSERT IGNORE} 静默跳过，因此可以无脑先调用再做后续条件更新，
     * 省去「先查有没有、再决定 insert」之间的竞态。
     */
    @Insert("""
            INSERT IGNORE INTO im_user_wallet (user_id, balance, total_recharged, total_consumed, deleted)
            VALUES (#{userId}, 0, 0, 0, 0)
            """)
    void insertIfAbsent(@Param("userId") Long userId);

    /**
     * 扣款：余额充足才扣得动，同时累加 {@code total_consumed}。返回 0 表示余额不足（或行不存在）。
     */
    @Update("""
            UPDATE im_user_wallet
            SET balance = balance - #{amount},
                total_consumed = total_consumed + #{amount},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND deleted = 0
              AND balance >= #{amount}
            """)
    int deduct(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 充值到账：加余额的同时累加 {@code total_recharged}，与只加余额的 {@link #creditBalance} 区分开——
     * 「累计充值」是运营口径的充值总额，后台赠送/调整不该混进去。
     */
    @Update("""
            UPDATE im_user_wallet
            SET balance = balance + #{amount},
                total_recharged = total_recharged + #{amount},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND deleted = 0
            """)
    int recharge(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 只加余额的入账（签到赠送、后台调整、收到转账与红包），不动累计充值与累计消费。
     */
    @Update("""
            UPDATE im_user_wallet
            SET balance = balance + #{amount},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND deleted = 0
            """)
    int creditBalance(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 用户之间转积分的支出：只减余额，<b>不动</b> {@code total_consumed}。
     *
     * <p>为什么不复用 {@link #deduct}：{@code total_consumed} 是运营口径的「向平台买服务的累计」
     * （送礼等），而转账/发红包只是把同一批积分从我的行挪到对方的行，平台一分钱都没多。
     * 把它计成消费会让「累计消费」失去意义，还会让「充值 100 、转出 100」看起来像消费了 100。
     *
     * <p>与 {@code deduct} 同样靠 {@code balance >= amount} 做行锁原子判定，返回 0 即没扣动（余额不足）。
     */
    @Update("""
            UPDATE im_user_wallet
            SET balance = balance - #{amount},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND deleted = 0
              AND balance >= #{amount}
            """)
    int debitBalance(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 每日签到：余额加赠金并把 {@code sign_last_date} 拨到今天，条件 {@code sign_last_date < today}
     * （或为 null）保证「一天只能签一次」——重复签到返回 0 行，业务层抛 {@code CHECKIN_ALREADY_DONE}。
     * 把去重与加钱合进同一条 UPDATE，避免「先判今天签没签、再更新」之间的竞态。
     */
    @Update("""
            UPDATE im_user_wallet
            SET balance = balance + #{bonus},
                sign_last_date = #{today},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND deleted = 0
              AND (sign_last_date IS NULL OR sign_last_date < #{today})
            """)
    int checkin(@Param("userId") Long userId, @Param("bonus") long bonus, @Param("today") LocalDate today);

    /**
     * 余额快照：扣款/入账的 UPDATE 生效后，在同一事务内回读一次，用于写 {@code balance_after}。
     * 同一事务里能读到本事务刚提交的更新值，不受其他事务干扰。
     */
    @Select("SELECT balance FROM im_user_wallet WHERE user_id = #{userId} AND deleted = 0")
    Long selectBalance(@Param("userId") Long userId);
}
