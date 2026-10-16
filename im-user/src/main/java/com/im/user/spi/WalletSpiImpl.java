package com.im.user.spi;

import com.im.common.domain.GiftBriefDTO;
import com.im.common.spi.WalletSpi;
import com.im.user.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link WalletSpi} 实现：把 im-user 持有的钱包与礼物目录，以窄接口暴露给 im-live 送礼。
 *
 * <p>纯委托 {@link WalletService}，不加任何业务逻辑——跨模块契约与本地领域服务各自演进，
 * 这里只做一次适配。扣款/入账自带 {@code @Transactional}，被 im-live 的送礼事务包进来后（{@code REQUIRED}）
 * 合并为同一事务，保证「扣钱包 + 记送礼流水」原子生效。
 */
@Service
@RequiredArgsConstructor
public class WalletSpiImpl implements WalletSpi {

    private final WalletService walletService;

    @Override
    public boolean enabled() {
        return walletService.enabled();
    }

    @Override
    public long getBalance(Long userId) {
        return walletService.getBalance(userId);
    }

    @Override
    public void deduct(Long userId, long amount, String type, Long refId, String remark) {
        walletService.deduct(userId, amount, type, refId, remark);
    }

    @Override
    public void credit(Long userId, long amount, String type, Long refId, String remark) {
        walletService.credit(userId, amount, type, refId, remark);
    }

    @Override
    public List<GiftBriefDTO> giftCatalog() {
        return walletService.giftCatalog();
    }

    @Override
    public GiftBriefDTO findGift(Long giftId) {
        return walletService.findGift(giftId);
    }
}
