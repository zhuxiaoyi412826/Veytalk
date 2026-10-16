package com.im.common.spi;

/**
 * 支付网关抽象：为钱包充值提供「到账」能力。
 *
 * <p>本项目不内置真实支付链路。未接入厂商时，im-user 用 {@code @ConditionalOnProperty} 注册一个
 * 模拟桩（{@code MockPaymentSpi}）直接按请求额到账以跑通联调；接真实支付时提供一个 {@code @Primary}
 * 实现即覆盖桩，业务代码不变。这一取向与语音转文字、邮件降级的 {@code mock-when-unconfigured} 完全一致。
 */
public interface PaymentSpi {

    /** 支付通道是否可用；桩恒 true，真实实现可据密钥配置情况返回 false */
    boolean available();

    /**
     * 发起一次充值并返回应到账的积分额。
     *
     * <p>本接口只负责「向支付渠道收款 / 授权」这一步，<b>不写钱包</b>：拿到返回额后由钱包侧
     * （{@code WalletService.credit}）统一入账与记流水，避免真实支付回调与业务侧重复加钱。
     *
     * @param userId 充值用户
     * @param amount 期望到账的积分额（已由业务层做上下界校验）
     * @return 实际应入账的积分额
     */
    long recharge(Long userId, long amount);
}
