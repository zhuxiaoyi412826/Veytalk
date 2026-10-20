package com.im.user.task;

import com.im.user.service.RedPacketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 红包过期退回：把超过 {@code im.wallet.packet-expire-hours} 仍没人领的余额退回发送人。
 *
 * <p>五分钟一轮而不是每秒：过期判定单位是小时，节拍再快也不会让用户早一秒拿回退款，
 * 而这一轮要做的事是<b>扫账本</b>（{@code im_wallet_transaction} 上按 type + create_time 聚合，
 * 目前没有专门索引），频率高了就是在给一张只增的表做周期性全表扫描。
 *
 * <p>吞掉一切异常的理由与 {@code LivePkTask} 相同：调度线程里抛出未捕获异常会让后续执行静默停摆，
 * 表现为「过期红包再也不退钱」，而日志里没有线索。这里不做补偿式重试——
 * 未退余额仍然留在账本上，下一轮扫描会原样再捞出来一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedPacketRefundTask {

    private final RedPacketService redPacketService;

    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void refund() {
        try {
            int refunded = redPacketService.refundExpired();
            if (refunded > 0) {
                log.info("[红包] 过期退回完成: {} 笔", refunded);
            }
        } catch (Exception e) {
            log.error("[红包] 过期退回异常", e);
        }
    }
}
