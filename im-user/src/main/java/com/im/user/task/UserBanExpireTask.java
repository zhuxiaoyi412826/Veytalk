package com.im.user.task;

import com.im.user.spi.AdminUserSpiImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 限时封禁到期自动解封：把 {@code im_user.disable_until} 已经过期的账号放回正常态。
 *
 * <p>三十秒一轮是「够快也够便宜」的折中：最短的一档封禁是 10 分钟，误差 30 秒没人会在意，
 * 而被封禁的账号本来就不该在等秒级精度——真按秒做只会把一次索引扫描变成每秒钟一次的索引扫描。
 * 扫描走 {@code idx_disable_until}，绝大多数用户这一列是 {@code NULL}，
 * 代价与「当前被封且带到期时间的人数」成正比，与用户总数无关，所以这一轮在空库上近乎免费。
 *
 * <p>注入的是 {@link AdminUserSpiImpl} 实现类而不是 {@code AdminUserSpi} 契约，这是刻意的：
 * 批量解封不放在契约上，别的模块就编译不到这个方法（理由逐条写在
 * {@code AdminUserSpiImpl#autoUnbanExpired} 的注释里，包括「为什么条件必须有 isNotNull」
 * 那条——它决定了一次注销过的账号会不会被这轮扫描救活）。
 *
 * <p>吞掉一切异常的理由与 {@link RedPacketRefundTask} 相同：调度线程里抛出未捕获异常
 * 会让后续执行静默停摆，表现为「到期了也解不开」，而日志里没有线索。这里不做补偿式重试——
 * 到期而未解封的行仍然留在那里，下一轮扫描会原样再捞出来一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBanExpireTask {

    private final AdminUserSpiImpl adminUserSpi;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void unbanExpired() {
        try {
            int unbanned = adminUserSpi.autoUnbanExpired();
            if (unbanned > 0) {
                log.info("[自动解封] 本轮恢复 {} 个到期账号", unbanned);
            }
        } catch (Exception e) {
            log.error("[自动解封] 扫描到期封禁异常", e);
        }
    }
}
