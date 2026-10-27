package com.im.admin.task;

import com.im.admin.service.RuntimeConfigService;
import com.im.admin.service.SensitiveWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把库里的系统配置与在线敏感词收敛到运行态（四期第四批）。
 *
 * <p>两个时机共用一套 service 方法：① 应用就绪后跑一次，把重启前存下的覆盖项和后台加的词装回来
 * （这时是全量应用，不看签名）；② 之后每 30 秒比一次签名，没变就整轮跳过。
 *
 * <h2>为什么用轮询而不是 Redis 发布订阅</h2>
 *
 * <p>本仓库到现在为止<b>没有任何</b> {@code convertAndSend} / {@code MessageListener} 的用法，
 * 缓存一致性靠的是三级缓存的短 TTL 收敛。为这一个后台功能引进一套 pub/sub 基础设施
 * （订阅通道、监听容器、断线重订阅）不划算，而它换来的收益只是把「最迟 30 秒后另一个节点生效」
 * 缩短成毫秒级——敏感词晚 30 秒生效不会让任何人受损，一条消息不会因为那 30 秒而追不回来。
 * 这个取向与限时封禁的自动解封（{@code UserBanExpireTask}，同样 30 秒）完全一致。
 *
 * <h2>异常一律吞掉，且两条链路各自独立</h2>
 *
 * <p>调度线程里抛出未捕获异常会让后续执行静默停摆，表现为「后台改了配置却永远不生效」，
 * 而日志里没有线索。两条链路分开设 try/catch 而不是合并成一个：{@code im_config} 读失败
 * （比如那张表还没建）不该连带让词库收敛也停掉——后者是更常被人用的那一个。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigSyncTask {

    private final RuntimeConfigService runtimeConfigService;
    private final SensitiveWordService sensitiveWordService;

    /**
     * 应用就绪后先全量应用一次。
     *
     * <p>挂在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：那时本模块之外的
     * bean 可能还没装配完（{@code ImProperties} 虽然已绑定，但推送通道、定时任务这些都在建），
     * 而覆盖运行态这件事必须发生在所有读取方各就各位之后。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void applyOnReady() {
        run("系统配置", runtimeConfigService::refreshFromDb);
        run("敏感词库", sensitiveWordService::reloadFromDb);
    }

    /** 之后每 30 秒比一次签名，只有真变过才动手 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void poll() {
        run("系统配置", runtimeConfigService::refreshFromDb);
        run("敏感词库", sensitiveWordService::refreshIfChanged);
    }

    private void run(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[运行态同步] {} 收敛失败，下一轮重试", name, e);
        }
    }
}
