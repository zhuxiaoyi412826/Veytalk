package com.im.common.spi;

import com.im.common.domain.GiftBriefDTO;

import java.util.List;

/**
 * 钱包与礼物目录的跨模块接口：钱包本体与礼物目录都由 im-user 持有，
 * im-live 送礼时经此接口扣款与取礼，避免 im-live 直接依赖 im-user 的实现类。
 *
 * <p>扣款与入账内部各带一条账本流水，调用方（送礼）把它放进自己的事务里即与业务写库原子生效
 * （单体同库同源，Spring 的 {@code REQUIRED} 传播会让 SPI 实现并入外层事务）。
 */
public interface WalletSpi {

    /** 钱包功能总开关，关闭后送礼/充值入口整体拒绝 */
    boolean enabled();

    /** 取余额，钱包行不存在时按 0 处理（首次访问由入账/扣款路径惰性建行） */
    long getBalance(Long userId);

    /**
     * 原子扣款：余额不足抛 {@code WALLET_BALANCE_NOT_ENOUGH}。
     *
     * @param amount 正数扣款额
     * @param type   账本类型，见 {@code ImConstants.WALLET_TX_*}
     * @param refId  关联业务 ID（如送礼记录 ID / 房间 ID），可空
     * @param remark 备注，可空
     */
    void deduct(Long userId, long amount, String type, Long refId, String remark);

    /** 入账：充值到账、签到赠送、后台调整都走这里，amount 为正数 */
    void credit(Long userId, long amount, String type, Long refId, String remark);

    /** 上架礼物目录，按 sort 升序，供前端礼簿渲染 */
    List<GiftBriefDTO> giftCatalog();

    /** 取单个上架礼物；不存在或已下架返回 null（送礼方据此回 GIFT_NOT_FOUND / GIFT_DISABLED） */
    GiftBriefDTO findGift(Long giftId);
}
