package com.im.user.service;

import com.im.user.dto.req.PacketSendRequest;
import com.im.user.dto.vo.PacketClaimVO;
import com.im.user.dto.vo.PacketVO;
import com.im.user.dto.vo.WalletActionVO;

/**
 * 红包：单聊独占一份、群聊拼手气，领取态在 Redis、钱在账本。
 *
 * <p>三件事各自的位置，决定了这套接口为什么长这样：
 * <ul>
 *   <li><b>钱</b>：发出时就从发送人余额真扣（一条 {@code packet} 负额流水），领取时才入账给领取人，
 *       中间的差额挂在红包池里；</li>
 *   <li><b>领取态</b>：谁领了、领到第几份，全在 Redis Hash 里，零 DDL，也是它能随 TTL 自清的原因；</li>
 *   <li><b>退款</b>：{@link #refundExpired()} 不读 Redis，而是按 {@code ref_id = packetId} 对账本求和，
 *       所以 Redis 整份丢掉也只会「红包领不了」，不会「钱回不来」。</li>
 * </ul>
 */
public interface RedPacketService {

    /**
     * 发红包：校验会话与额度 → 预切分金额 → 扣款 → 建 Redis 池 → 发出红包消息。
     *
     * @param userId  发送人（当前登录用户）
     * @param request 会话、总额、份数与祝福语
     * @return 新余额与已落库的红包消息
     */
    WalletActionVO send(Long userId, PacketSendRequest request);

    /**
     * 抢红包：一个名额只属于一个人，抢到即入账。
     *
     * @return 抢到的金额与入账后的余额
     */
    PacketClaimVO claim(Long userId, Long packetId);

    /**
     * 红包详情与领取明细（点开气泡时现取）。
     *
     * <p>已过期但 Redis 里还留着骨架时<b>不报错</b>，而是返回 {@code status = expired}：
     * 用户点开一个自己没来得及领的红包，看到的该是「已过期退回」，而不是一个刺眼的错误弹窗。
     */
    PacketVO detail(Long userId, Long packetId);

    /**
     * 扫描过期未领完的红包并把余额退回发送人，由 {@code RedPacketRefundTask} 定时调用。
     *
     * @return 本轮实际退回的红包个数
     */
    int refundExpired();
}
