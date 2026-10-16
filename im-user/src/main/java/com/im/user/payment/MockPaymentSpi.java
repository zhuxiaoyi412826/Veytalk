package com.im.user.payment;

import com.im.common.spi.PaymentSpi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付通道演示桩：仅在 {@code im.wallet.mock-when-unconfigured=true} 时注册。
 *
 * <p>定位与 {@code MockSpeechToTextSpi}（{@code im.intelligence.mock-when-unconfigured}）、
 * {@code captcha.mockMailWhenUnconfigured} 完全一致——没有真实支付厂商时，让「充值 → 到账 → 送礼」
 * 这条链路可以被完整跑通与演示：桩直接把请求额原样「到账」，不产生任何真实资金流转。
 *
 * <p>生产环境必须关掉本开关（默认即关）：开着它等于任何人喊多少就白送多少积分。届时若没有任何
 * {@link PaymentSpi} bean，{@code WalletService.recharge} 会优雅降级回 {@code PAYMENT_UNAVAILABLE}。
 *
 * <p>接入真实厂商：提供一个自己的 {@link PaymentSpi} 实现（必要时标 {@code @Primary}），本桩让位或被直接关闭。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "im.wallet", name = "mock-when-unconfigured", havingValue = "true")
public class MockPaymentSpi implements PaymentSpi {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public long recharge(Long userId, long amount) {
        // 模拟桩不做任何真实收款，直接按请求额「到账」；入账与流水由钱包侧完成
        log.warn("[模拟支付] 用户 {} 充值 {} 积分（mock-when-unconfigured=true，非真实收款，生产务必关闭）", userId, amount);
        return amount;
    }
}
