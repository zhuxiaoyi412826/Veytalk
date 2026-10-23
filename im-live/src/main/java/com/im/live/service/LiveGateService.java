package com.im.live.service;

import com.im.live.dto.po.LiveGatePO;
import com.im.live.dto.vo.LiveGateVO;

/**
 * 付费 / 密码直播门禁：<b>零 DDL</b>，配置与解锁态都只存 Redis。
 *
 * <h2>门禁到底闸在哪</h2>
 *
 * <p>真正的闸门是<b>签名播放地址</b>：{@code LiveRoomServiceImpl.toVO} 只对「已解锁 / 主播本人」
 * 下发 {@code playUrl} 与 {@code danmakuWs}。播放地址的 path 里带着本场的 {@code streamKey}
 * （能力凭证，见 {@code LiveRoom} 类注释）且带签名，观众拿不到 playUrl 就拉不到任何分片——
 * 所以本服务不参与媒体面，只决定「这个人这一场有没有资格拿到地址」。弹幕 WS 侧另有一道
 * 拒绝（{@code LiveDanmakuHandler}）做纵深防御，但那道闸的意义只是「不给没解锁的人潜水聊天」，
 * 不是防看（防看靠 playUrl）。
 *
 * <h2>已知边界（有意为之）</h2>
 *
 * <ul>
 *   <li><b>付费只扣观众、不给主播入账</b>：与送礼同口径，积分是封闭模拟币、不进主播钱包不可提现，
 *       因此不引入一条提现路径。入场费只作为一条 {@code live_pay} 负额流水记在观众自己账上；</li>
 *   <li><b>解锁态 TTL 与门禁同量级、不随心跳续</b>：主播续心跳续的是 {@code gate} 键，
 *       观众 {@code unlock} 键是独立的一次性写入。观众在同一场里重进凭键免解锁；跨 TTL（默认 12 小时）
 *       后需重新解锁，与「门禁是这一场的属性」一致。</li>
 * </ul>
 */
public interface LiveGateService {

    /**
     * 开播时按请求落门禁配置：给了密码即密码房，否则给了正价即付费房，两者都没有则清除门禁（公开房）。
     *
     * <p>password 与 price 同时给时按密码优先（price 被忽略），与开播请求 DTO 上的约定一致。
     *
     * @param roomId   房间 ID
     * @param anchorId 主播 ID（仅日志用）
     * @param password 明文密码，可空——服务端内部转 SHA-256 摘要后落 Redis，不留明文
     * @param price    入场积分价，可空或 ≤0 表示不设价
     */
    void seed(Long roomId, Long anchorId, String password, Long price);

    /** 心跳续期：把门禁键的 TTL 往后推，让配置与直播同生命周期。无门禁键时静默跳过。 */
    void touch(Long roomId);

    /** 取门禁配置；无门禁（键不存在/已过期）返回 null。 */
    LiveGatePO configOf(Long roomId);

    /** 该用户是否已解锁本房间（有 unlock 键即为真）。 */
    boolean isUnlocked(Long roomId, Long userId);

    /**
     * 该用户是否被本房间门禁锁住：存在门禁配置且未解锁即为 true（主播免判由调用方自己排除）。
     *
     * <p>供弹幕 WS 侧纵深防御用：让 handler 不必认识 {@code LiveGatePO}。playUrl 不下发是主闸门，
     * 这里拦的是「手动拼 {@code /ws/live} URL 绕过 REST 进来潜水」那条路。
     */
    boolean isLockedFor(Long roomId, Long userId);

    /**
     * 解锁密码房：校验模式与密码摘要，通过后为当前用户种解锁标记。
     *
     * @throws BusinessException 房间非密码房（{@code LIVE_GATE_NOT_PASSWORD}）或密码错误（{@code LIVE_GATE_PASSWORD_WRONG}）
     */
    void unlockByPassword(Long roomId, Long userId, String password);

    /**
     * 付费房解锁：扣观众入场积分后种解锁标记。主播本人（门禁配置里的 anchorId）恒免，不扣自己。
     *
     * <p>幂等：已解锁的重复调用直接成功返回，不再扣第二次钱。
     *
     * @throws BusinessException 房间非付费房（{@code LIVE_GATE_NOT_PAID}）；余额不足由 {@code WalletSpi} 抛
     */
    void unlockByPay(Long roomId, Long userId);

    /** 关播/撤房时清掉门禁配置（解锁标记靠 TTL 自然消失，不逐个删）。 */
    void drop(Long roomId);

    /** 组装给前端的门禁信息：无门禁返回 {@code type=none}。永不含密码本体或摘要。 */
    LiveGateVO describe(LiveGatePO po);
}
