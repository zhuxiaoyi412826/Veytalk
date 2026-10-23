package com.im.live.task;

import com.im.live.service.LivePkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PK 节拍：每秒广播一次进度、到点结算、回收已结束的会话。
 *
 * <p>不跟 {@link LiveCheckTask} 的 2 秒合流：那两件事（心跳超时、点赞冲刷）都是「够用就行」，
 * 而倒计时是给人看的，节拍比显示粗一档就会出现「剩余 3 秒卡住然后突然归零」。
 * 分开一个任务也意味着 PK 出问题时可以单独关，不必牵连房间生命周期。
 *
 * <p>吞掉一切异常与 {@link LiveCheckTask} 同理：调度线程里抛出未捕获异常会让后续执行静默停摆，
 * 表现为「进度条再也不动、PK 永远不结算」，而日志里没有线索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LivePkTask {

    private final LivePkService pkService;

    @Scheduled(fixedDelay = 1_000L)
    public void tick() {
        try {
            int sent = pkService.tick();
            if (sent > 0 && log.isDebugEnabled()) {
                log.debug("PK 节拍: 广播 {} 场", sent);
            }
        } catch (Exception e) {
            log.error("PK 节拍异常", e);
        }
    }
}
