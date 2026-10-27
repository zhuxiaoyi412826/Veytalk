package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminGiftDTO;
import com.im.common.domain.AdminWalletDTO;
import com.im.common.domain.AdminWalletOverviewDTO;
import com.im.common.domain.AdminWalletQueryDTO;
import com.im.common.domain.AdminWalletTxDTO;
import com.im.common.domain.AdminWalletTxQueryDTO;

import java.util.List;

/**
 * 后台钱包与礼物目录契约，由 im-user 实现（{@code WalletAdminSpiImpl}）。
 *
 * <h2>为什么钱要单独设一个点，而不并进 {@code system:user:manage}</h2>
 *
 * <p>管一个人的「能不能登录」和管他的「余额是多少」是两种风险：前者错了可以恢复，
 * 后者错了是<b>凭空造出货币</b>。所以 {@code adjust} 是本批唯一带钱的写方法，
 * 挂在 {@code ImConstants.PERM_SYSTEM_WALLET} 上，且每次调用都由实现方落一条
 * {@code type=adjust} 的账本流水——<b>后台调账没有「只改数字不记一笔」这条路</b>，
 * 想抹掉痕迹只能删库，而删库是另一个更严重的罪名。
 *
 * <h2>调账走的是用户侧同一套代码</h2>
 *
 * <p>入账与扣款都复用 {@code WalletService} 的 {@code credit} / {@code debit}，
 * 也就是<b>条件 UPDATE + 同事务补流水</b>那一段：余额恒为非负（{@code balance >= 扣款额}），
 * {@code balance_after} 是同事务回读的快照。后台不另开一条「直接写 balance」的捷径，
 * 否则账本与余额就会有两套口径，对账时谁也说服不了谁。
 *
 * <h2>充值上限那条配置项管不到这里</h2>
 *
 * <p>{@code im.wallet.recharge-max} 只约束用户自助充值（{@code recharge()} 里校验）。
 * 本契约的 {@code adjust} 刻意<b>不受</b>那个上限约束——它约束的是「用户能自己往口袋里塞多少」，
 * 而管理员调账是另一条授权链，把上限套上去只会逼管理员把一笔大额调成十几笔小额、
 * 反而把账本搞花。要夹住它靠的是权限点本身与 {@code remark} 必填。
 *
 * <h2>概览那几个 SUM 是全表扫描</h2>
 *
 * <p>{@code im_user_wallet} 只有主键，没有余额索引，{@code overview()} 里的
 * {@code SUM(balance)} / {@code SUM(total_recharged)} / {@code SUM(total_consumed)}
 * 必然扫全表。可接受的理由是行数等于用户数且列都是定长 BIGINT，与
 * {@code AdminMetricsSpi} 那几个计数同量级；<b>不缓存</b>，因为后台一进这一屏就想看当下的数，
 * 缓存反而会让「刚调完账概览没变」变成一条假故障。真要优化是加覆盖索引，属于未来 DDL。
 *
 * <p>{@code giftCatalog()} 返回<b>含已下架</b>的全量目录：下架只是让用户的礼簿里看不见，
 * 后台要能看到并把它重新上架；历史送礼流水冗余了礼物名与成交单价，下架与改价都不会影响历史展示。
 */
public interface AdminWalletSpi {

    /**
     * 顶部概览：钱包开关、开户数、三项累计、今日流水与今日调账笔数。
     *
     * <p>{@code walletEnabled} 取的是运行态 {@code ImProperties.wallet.enabled}，
     * 也就是「系统配置」那一屏改完生效后的值，而不是配置文件里的初值——
     * 后台看到关着就真的关着。
     */
    AdminWalletOverviewDTO overview();

    /**
     * 钱包台账翻页。
     *
     * <p>排序固定 {@code balance DESC, user_id ASC}：这一屏第一眼看的是「谁口袋里钱最多」，
     * 那既是运营关心的重点，也是异常调账最可能的落点。{@code user_id} 兜底是因为
     * 余额相同的人很多，排序键不唯一会让翻页出现重复行或漏行。
     */
    PageResult<AdminWalletDTO> pageWallets(AdminWalletQueryDTO query);

    /**
     * 单个用户的钱包档案，带最近若干条流水。
     *
     * @return 该用户从未有过钱包行时返回 {@code null}（不惰性建行——后台读一个没充过值的号
     *         不应该顺手往库里插一行）
     */
    AdminWalletDTO detail(Long userId);

    /**
     * 账本流水翻页，全站口径（{@code query.userId} 为空时跨用户检索）。
     *
     * <p>倒序按主键（雪花 ID 随时间递增）而不是 {@code create_time}：后者只在
     * {@code idx_user_time} 的第二列，跨用户排序用不上，走主键倒扫能边扫边停。
     */
    PageResult<AdminWalletTxDTO> pageTransactions(AdminWalletTxQueryDTO query);

    /**
     * 后台调整余额：{@code delta} 为正入账、为负扣减，为 0 直接被拒。
     *
     * <p>扣减时余额不足会抛 {@code WALLET_BALANCE_NOT_ENOUGH}——后台<b>不能</b>把余额调成负数，
     * 那条不变量是整个账本可信赖的前提，为管理员开后门等于取消它。
     *
     * @param delta   有符号变动额
     * @param remark  事由，实现方夹为必填并截断到列宽
     * @param adminId 操作人用户 ID，写进流水备注，供事后追责
     * @return 调整后的最新档案（含变动后的余额与刚写入的那条流水）
     */
    AdminWalletDTO adjust(Long userId, long delta, String remark, Long adminId);

    /** 礼物目录全量（含已下架），按 {@code sort} 升序、{@code id} 升序 */
    List<AdminGiftDTO> giftCatalog();

    /**
     * 礼物上架 / 下架。
     *
     * <p>只改 {@code status}，不改价格、不改名、不删行：改价会让「今天卖 100、明天卖 10」
     * 这种问题变成两个人各执一词，而删除会让历史送礼流水的 {@code gift_id} 指向一个空位。
     *
     * @return 改动后的礼物行；{@code giftId} 不存在时返回 {@code null}
     */
    AdminGiftDTO setGiftStatus(Long giftId, boolean onShelf);
}
