package com.im.user.task;

import com.im.user.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 登录流水清理：把 {@code im_login_log} 里超过保留期的行物理删掉。
 *
 * <p>这张表只写不删，是全站增长最快的一张——每次登录、每一次顶号、每一次失败的密码尝试都留一行。
 * 保留期读自 {@code im.account.login-log-keep-days}（默认 180 天，填 0 表示永不清理），
 * 出盗号投诉时回溯半年足够，再往后的行既不解决问题，只是把表越养越慢。
 *
 * <p>六小时一轮而不是每天一次：一次最多删 500 行（{@code DeviceServiceImpl.PURGE_BATCH}），
 * 首次上线或者长期没清过的库里可能堆了几十万行，每天一轮要许多天才追得上；
 * 而追上之后每轮都是空扫描，代价可以忽略。与 {@link UserBanExpireTask} 同理，
 * 调度线程里抛出的未捕获异常会让后续执行静默停摆，所以整段吞掉异常只留日志——
 * 这一轮没删掉的行下一轮照样在原处等着，不需要补偿式重试。
 *
 * <p>注入的是 {@link DeviceService} 契约而不是实现类：{@code purgeExpiredLogs} 本来就是对外能力之一
 * （后台将来要能手动触发），不像批量解封那样需要刻意藏在实现里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginLogCleanTask {

    private final DeviceService deviceService;

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void purge() {
        try {
            int purged = deviceService.purgeExpiredLogs();
            if (purged > 0) {
                log.info("[登录流水] 本轮清理过期记录 {} 条", purged);
            }
        } catch (Exception e) {
            log.error("[登录流水] 清理过期记录异常", e);
        }
    }
}
