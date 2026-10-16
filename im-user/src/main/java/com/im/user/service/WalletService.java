package com.im.user.service;

import com.im.common.domain.GiftBriefDTO;
import com.im.user.dto.vo.WalletVO;

import java.util.List;

/**
 * 钱包与礼物目录领域服务（im-user 本地接口）。
 *
 * <p>余额原子的加减（{@link #credit}/{@link #deduct}）与业务编排（{@link #recharge}/{@link #checkin}）都收在这里；
 * 跨模块暴露给 im-live 送礼的窄接口由 {@code WalletSpiImpl} 委托本服务的余额方法与目录方法实现。
 */
public interface WalletService {

    /** 钱包功能总开关 */
    boolean enabled();

    /** 组装钱包视图：余额 + 礼物目录 + 前端所需配置 */
    WalletVO getWallet(Long userId);

    /** 取余额，钱包行不存在按 0 处理 */
    long getBalance(Long userId);

    /** 入账（充值/签到/后台调整），amount 为正数；写余额与账本流水，同事务 */
    void credit(Long userId, long amount, String type, Long refId, String remark);

    /** 扣款，amount 为正数；余额不足抛 {@code WALLET_BALANCE_NOT_ENOUGH}；写余额与账本流水，同事务 */
    void deduct(Long userId, long amount, String type, Long refId, String remark);

    /** 充值：校验开关与额度上下界 → 走支付通道 → 入账；返回刷新后的钱包视图 */
    WalletVO recharge(Long userId, long amount);

    /** 每日签到：按自然日去重赠送积分；重复签到抛 {@code CHECKIN_ALREADY_DONE}；返回刷新后的钱包视图 */
    WalletVO checkin(Long userId);

    /** 上架礼物目录，按 sort 升序 */
    List<GiftBriefDTO> giftCatalog();

    /** 取单个上架礼物；不存在或已下架返回 null */
    GiftBriefDTO findGift(Long giftId);
}
