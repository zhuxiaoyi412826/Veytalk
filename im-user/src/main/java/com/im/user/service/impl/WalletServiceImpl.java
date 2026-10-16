package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.domain.GiftBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.PaymentSpi;
import com.im.user.dto.vo.WalletVO;
import com.im.user.entity.Gift;
import com.im.user.entity.UserWallet;
import com.im.user.entity.WalletTransaction;
import com.im.user.mapper.GiftMapper;
import com.im.user.mapper.UserWalletMapper;
import com.im.user.mapper.WalletTransactionMapper;
import com.im.user.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 钱包领域服务实现。
 *
 * <p>余额变动全部经 {@link UserWalletMapper} 的条件 UPDATE 完成，再在<b>同一事务</b>里补一条账本流水，
 * 保证「钱动了、账也记了」这两件事原子生效——不会出现扣了钱没流水、或记了流水钱没扣的对不上账。
 * 送礼场景下 im-live 的 {@code GiftService} 会把自己的事务包进来（Spring {@code REQUIRED} 传播），
 * 于是「扣款 + 送礼流水 + 记录」三段一起提交或一起回滚。
 *
 * <p>{@code balanceAfter} 采用「UPDATE 生效后同事务内回读一次」的取法：本事务能读到自己刚写入的值，
 * 简单可靠，代价是多一次单行主键查询，可接受。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final UserWalletMapper walletMapper;
    private final WalletTransactionMapper transactionMapper;
    private final GiftMapper giftMapper;
    private final ImProperties imProperties;
    /** 支付通道可能一个实现都没有（未接厂商且关了模拟桩）：用 ObjectProvider 优雅缺席，缺则充值回 PAYMENT_UNAVAILABLE */
    private final ObjectProvider<PaymentSpi> paymentSpiProvider;

    @Override
    public boolean enabled() {
        return imProperties.getWallet().isEnabled();
    }

    @Override
    public WalletVO getWallet(Long userId) {
        ImProperties.Wallet wallet = imProperties.getWallet();
        UserWallet row = walletMapper.selectById(userId);
        long balance = row == null || row.getBalance() == null ? 0L : row.getBalance();
        boolean signedToday = row != null && LocalDate.now().equals(row.getSignLastDate());
        return WalletVO.builder()
                .enabled(wallet.isEnabled())
                .balance(balance)
                .gifts(giftCatalog())
                .checkinBonus(wallet.getCheckinBonus())
                .signedToday(signedToday)
                .rechargeMin(wallet.getRechargeMin())
                .rechargeMax(wallet.getRechargeMax())
                .build();
    }

    @Override
    public long getBalance(Long userId) {
        if (userId == null) {
            return 0L;
        }
        Long balance = walletMapper.selectBalance(userId);
        return balance == null ? 0L : balance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void credit(Long userId, long amount, String type, Long refId, String remark) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "userId 不能为空");
        BusinessException.throwIf(amount <= 0, ResultCode.BAD_REQUEST, "入账额必须为正数");
        walletMapper.insertIfAbsent(userId);
        // 充值单独走 recharge 以累计 total_recharged；其余入账类型（签到赠送、后台调整等）只加余额
        if (ImConstants.WALLET_TX_RECHARGE.equals(type)) {
            walletMapper.recharge(userId, amount);
        } else {
            walletMapper.creditBalance(userId, amount);
        }
        writeTransaction(userId, type, amount, refId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deduct(Long userId, long amount, String type, Long refId, String remark) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "userId 不能为空");
        BusinessException.throwIf(amount <= 0, ResultCode.BAD_REQUEST, "扣款额必须为正数");
        walletMapper.insertIfAbsent(userId);
        int rows = walletMapper.deduct(userId, amount);
        BusinessException.throwIf(rows == 0, ResultCode.WALLET_BALANCE_NOT_ENOUGH);
        writeTransaction(userId, type, -amount, refId, remark);
    }

    @Override
    public WalletVO recharge(Long userId, long amount) {
        ImProperties.Wallet wallet = imProperties.getWallet();
        BusinessException.throwUnless(wallet.isEnabled(), ResultCode.WALLET_DISABLED);
        BusinessException.throwIf(
                amount < wallet.getRechargeMin() || amount > wallet.getRechargeMax(),
                ResultCode.RECHARGE_AMOUNT_INVALID);
        PaymentSpi payment = paymentSpiProvider.getIfAvailable();
        BusinessException.throwUnless(payment != null && payment.available(), ResultCode.PAYMENT_UNAVAILABLE);
        // 支付通道只负责收款/授权并返回应到账额，入账与流水统一交给钱包侧，避免真实回调与业务侧重复加钱
        long credited = payment.recharge(userId, amount);
        credit(userId, credited, ImConstants.WALLET_TX_RECHARGE, null, "充值到账");
        return getWallet(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletVO checkin(Long userId) {
        ImProperties.Wallet wallet = imProperties.getWallet();
        BusinessException.throwUnless(wallet.isEnabled(), ResultCode.WALLET_DISABLED);
        long bonus = wallet.getCheckinBonus();
        walletMapper.insertIfAbsent(userId);
        // 去重与加钱合进一条 UPDATE：影响 0 行即代表今天已签过
        int rows = walletMapper.checkin(userId, bonus, LocalDate.now());
        BusinessException.throwIf(rows == 0, ResultCode.CHECKIN_ALREADY_DONE);
        writeTransaction(userId, ImConstants.WALLET_TX_CHECKIN, bonus, null, "每日签到赠送");
        return getWallet(userId);
    }

    @Override
    public List<GiftBriefDTO> giftCatalog() {
        List<Gift> gifts = giftMapper.selectList(Wrappers.<Gift>lambdaQuery()
                .eq(Gift::getStatus, Gift.STATUS_ON)
                .orderByAsc(Gift::getSort)
                .orderByAsc(Gift::getId));
        List<GiftBriefDTO> result = new ArrayList<>(gifts.size());
        for (Gift gift : gifts) {
            result.add(toBrief(gift));
        }
        return result;
    }

    @Override
    public GiftBriefDTO findGift(Long giftId) {
        if (giftId == null) {
            return null;
        }
        Gift gift = giftMapper.selectById(giftId);
        if (gift == null || !Integer.valueOf(Gift.STATUS_ON).equals(gift.getStatus())) {
            return null;
        }
        return toBrief(gift);
    }

    /** 余额 UPDATE 生效后同事务回读一次，写一条带余额快照的账本流水。amount 有符号（支出为负）。 */
    private void writeTransaction(Long userId, String type, long signedAmount, Long refId, String remark) {
        Long after = walletMapper.selectBalance(userId);
        WalletTransaction tx = new WalletTransaction();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setAmount(signedAmount);
        tx.setBalanceAfter(after == null ? 0L : after);
        tx.setRefId(refId);
        tx.setRemark(remark);
        transactionMapper.insert(tx);
    }

    private GiftBriefDTO toBrief(Gift gift) {
        return GiftBriefDTO.builder()
                .giftId(gift.getId())
                .name(gift.getName())
                .icon(gift.getIcon())
                .price(gift.getPrice())
                .kind(gift.getKind())
                .build();
    }
}
