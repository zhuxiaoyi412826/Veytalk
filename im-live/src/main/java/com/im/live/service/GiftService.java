package com.im.live.service;

import java.util.Map;

/**
 * 直播送礼服务：观众在直播中给主播送礼，扣积分、记流水、广播飘屏。
 */
public interface GiftService {

    /**
     * 送出一份礼物。
     *
     * <p>整笔在一个事务里原子生效：经 {@code WalletSpi} 扣款 + 写 {@code im_gift_record} + 累加房间礼物热度，
     * 任一失败全部回滚；飘屏广播在事务提交后才发出（仿消息模块约束，避免「没落库却飘屏」）。
     *
     * @param userId 送礼人（当前登录用户）
     * @param roomId 直播间 ID
     * @param giftId 礼物 ID
     * @param count  数量，服务层夹 {@code [1, im.wallet.max-gift-count]}
     * @return 送礼结果（余额快照 + 本次成交概要），供前端刷新展示
     */
    Map<String, Object> sendGift(Long userId, Long roomId, Long giftId, int count);
}
