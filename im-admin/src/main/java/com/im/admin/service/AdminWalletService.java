package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminGiftDTO;
import com.im.common.domain.AdminGiftRecordDTO;
import com.im.common.domain.AdminGiftRecordQueryDTO;
import com.im.common.domain.AdminWalletDTO;
import com.im.common.domain.AdminWalletOverviewDTO;
import com.im.common.domain.AdminWalletQueryDTO;
import com.im.common.domain.AdminWalletTxDTO;
import com.im.common.domain.AdminWalletTxQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminLiveSpi;
import com.im.common.spi.AdminWalletSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 后台钱包与礼物：账本、流水、调账、礼物目录与上下架、送礼流水。
 *
 * <p>薄封装，本类一行 SQL 都不写：钱在 {@code im_user_wallet}、礼物目录在 {@code im_gift}
 * 归 im-user，送礼流水在 {@code im_gift_record} 归 im-live，两边各经
 * {@link AdminWalletSpi} 与 {@link AdminLiveSpi} 交付。
 *
 * <p><b>为什么要拿两个 {@code ObjectProvider}</b>：「钱包与礼物」是一屏三个页签
 * （钱包台账 / 送礼流水 / 礼物目录），但底层两张表在两个模块里。把它们并成一个 SPI
 * 就得让 im-user 去读 {@code im_gift_record}，那是星型架构明确不允许的横向依赖；
 * 让送礼流水挂在直播那一屏的入口下，又会让「有 system:wallet 没 system:live 的人」
 * 看不到本该属于钱包视图的东西。所以由本类做装配：送礼页签只在 {@code AdminLiveSpi}
 * 装配上时可用，取不到时给一句明确的「直播模块未装配」而不是空列表——
 * 空列表会让人以为「今天没人送礼」，那是个假答案。
 *
 * <p>调账的落库与流水补充都在 im-user 内部完成（复用 {@code WalletService#credit/debit}），
 * 本类只负责把 {@code SecurityUtil} 取来的操作人 ID 传下去：钱的写路径必须有操作人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWalletService {

    private final ObjectProvider<AdminWalletSpi> walletSpiProvider;
    private final ObjectProvider<AdminLiveSpi> liveSpiProvider;

    public AdminWalletOverviewDTO overview() {
        return requireWalletSpi().overview();
    }

    public PageResult<AdminWalletDTO> page(AdminWalletQueryDTO query) {
        return requireWalletSpi().pageWallets(query);
    }

    /**
     * 单个用户的钱包档案。
     *
     * @return 该用户从未开过钱包行时为 {@code null}——不是错误：后台读一个没充过值的号，
     *         不该顺手往库里插一行（{@code detail} 的实现方也不惰性建行）
     */
    public AdminWalletDTO detail(Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "用户 ID 必填");
        return requireWalletSpi().detail(userId);
    }

    public PageResult<AdminWalletTxDTO> pageTransactions(AdminWalletTxQueryDTO query) {
        return requireWalletSpi().pageTransactions(query);
    }

    /**
     * 后台调账：正数入账、负数扣减、0 直接被实现方拒。
     *
     * <p>返回的是调整后的最新档案（含新余额与刚写入的那条流水），前端据此刷新那一行，
     * 不需要再查一次去猜「到底加成功了没有」。
     */
    public AdminWalletDTO adjust(Long userId, Long delta, String reason, Long adminId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "用户 ID 必填");
        BusinessException.throwIf(delta == null, ResultCode.BAD_REQUEST, "调整额度必填");
        AdminWalletDTO wallet = requireWalletSpi().adjust(userId, delta, reason, adminId);
        log.info("[后台钱包管理] 调账完成: userId={}, delta={}, admin={}", userId, delta, adminId);
        return wallet;
    }

    /** 礼物目录全量，含已下架的（后台要能看到并重新上架） */
    public List<AdminGiftDTO> giftCatalog() {
        return requireWalletSpi().giftCatalog();
    }

    /** 礼物上架 / 下架：只改状态，不改价、不改名、不删行 */
    public AdminGiftDTO setGiftStatus(Long giftId, boolean onShelf) {
        BusinessException.throwIf(giftId == null, ResultCode.BAD_REQUEST, "礼物 ID 必填");
        AdminGiftDTO gift = requireWalletSpi().setGiftStatus(giftId, onShelf);
        if (gift == null) {
            throw new BusinessException(ResultCode.GIFT_NOT_FOUND);
        }
        log.info("[后台钱包管理] 礼物{}: giftId={}", onShelf ? "上架" : "下架", giftId);
        return gift;
    }

    /**
     * 送礼流水翻页：数据在 im-live 手里，所以走 {@link AdminLiveSpi}。
     *
     * <p>只读，原因写在 {@code AdminLiveSpi#pageGiftRecords} 的注释里——
     * 那是账本，改一笔等于篡改他人之间的交易记录。
     */
    public PageResult<AdminGiftRecordDTO> pageGiftRecords(AdminGiftRecordQueryDTO query) {
        AdminLiveSpi spi = liveSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "直播模块未装配，无法查询送礼流水");
        }
        return spi.pageGiftRecords(query);
    }

    private AdminWalletSpi requireWalletSpi() {
        AdminWalletSpi spi = walletSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "钱包模块未装配，后台无法管理钱包");
        }
        return spi;
    }
}
