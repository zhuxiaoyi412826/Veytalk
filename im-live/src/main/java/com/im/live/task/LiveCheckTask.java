package com.im.live.task;

import com.im.live.service.LiveRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 直播巡检：心跳超时自动关播 + 点赞合并计数冲刷。
 *
 * <p>两件事共用一个 2 秒节拍：
 * <ul>
 *   <li>点赞冲刷要「够勤」——观众点了赞希望很快看到数字涨，2 秒是体感即时与帧数的折中；</li>
 *   <li>心跳超时判定要「够懒」——超时阈值是 heartbeatSeconds×factor（默认 60 秒）量级，
 *       2 秒扫一遍直播中的房间只是几次 {@code EXISTS}，成本可忽略。</li>
 * </ul>
 * 合成一个任务意味着单条日志线索，排查「房间为什么被关」时只看一处。
 *
 * <p>巡检必须吞掉一切异常：调度线程池里抛出未捕获异常会让后续执行静默停摆，
 * 表现为「点赞数字再也不涨、断播的房间永远挂着」，且日志里没有线索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveCheckTask {

    private final LiveRoomService roomService;

    @Scheduled(fixedDelay = 2_000L)
    public void check() {
        try {
            roomService.expireTimeouts();
        } catch (Exception e) {
            log.error("直播巡检异常", e);
        }
    }
}
