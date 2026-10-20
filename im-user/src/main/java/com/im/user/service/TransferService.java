package com.im.user.service;

import com.im.user.dto.req.TransferRequest;
import com.im.user.dto.vo.WalletActionVO;

/**
 * 用户间转账（单聊即时到账）。
 *
 * <p>与送礼（{@code im-live} 的 GiftService）的区别：送礼是「向平台买服务」，扣的钱计入 {@code total_consumed}；
 * 转账只是把钱从我的钱包行挪到对方的钱包行，平台一分没收，所以走 {@code WalletService#debit} 而不是 {@code deduct}。
 */
public interface TransferService {

    /**
     * 转账：校验开关与会话 → 扣款与入账（同事务） → 发出转账消息。
     *
     * <p>即时到账，没有「待领取」环节：单聊转账做成需要点一下才到账，只会多出「对方以为已经给了、
     * 我以为还没收到」的争执，而单聊双方本来就在同一个会话里，一句话就能确认。
     *
     * @param userId  付款方（当前登录用户）
     * @param request 会话、金额与附言
     * @return 新余额与已落库的转账消息
     */
    WalletActionVO transfer(Long userId, TransferRequest request);
}
