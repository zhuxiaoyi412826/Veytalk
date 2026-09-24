package com.im.remote.task;

import com.im.remote.config.RemoteProperties;
import com.im.remote.manager.AgentRegistry;
import com.im.remote.service.RemoteRelayService;
import com.im.remote.service.RemoteSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程通道巡检：未认证连接回收、Agent 心跳超时、会话空闲超时、邀请超时。
 *
 * <p>四类超时共用一个 30 秒节拍：这个功能对精度不敏感（超时阈值都是分钟级），
 * 单任务意味着单条日志线索，排查「会话为什么被断」时只需要看一处。
 *
 * <p>每一项检查都是防御性的——正常路径下 Agent 会主动 pong、两端会正常发
 * session-end；这里兜住的是「半开连接」：TCP 层没断但进程已经死了，
 * 唯一能发现它的只有超时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteIdleCheckTask {

    private final AgentRegistry agentRegistry;
    private final RemoteRelayService relayService;
    private final RemoteSessionService sessionService;
    private final RemoteProperties properties;

    /** 握手后多少秒内必须发 auth，否则视为探测连接 */
    private static final long AUTH_GRACE_MS = 10_000L;

    @Scheduled(fixedDelay = 30_000L)
    public void check() {
        try {
            sweepAgents();
            sweepBindings();
            sessionService.expireInvitingTimeouts();
        } catch (Exception e) {
            // 巡检必须吞掉一切异常：调度线程池里抛出未捕获异常会让后续执行静默停摆
            log.error("远程通道巡检异常", e);
        }
    }

    private void sweepAgents() {
        long heartbeatTimeoutMs = properties.getHeartbeatIntervalSeconds() * 3000L;
        long now = System.currentTimeMillis();
        List<AgentRegistry.AgentInfo> stale = new ArrayList<>();
        for (AgentRegistry.AgentInfo agent : agentRegistry.snapshot()) {
            if (!agent.isAuthed()) {
                if (now - agent.getLastActiveMs() > AUTH_GRACE_MS) {
                    stale.add(agent);
                }
            } else if (now - agent.getLastActiveMs() > heartbeatTimeoutMs) {
                stale.add(agent);
            }
        }
        for (AgentRegistry.AgentInfo agent : stale) {
            log.info("回收超时 Agent 连接: userId={}, deviceId={}, authed={}",
                    agent.getUserId(), agent.getDeviceId(), agent.isAuthed());
            try {
                agent.getSession().close();
            } catch (Exception ignored) {
                // afterConnectionClosed 若已触发，注册表清理已完成；这里无需补偿
            }
        }
    }

    private void sweepBindings() {
        long idleTimeoutMs = properties.getIdleTimeoutMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        for (RemoteRelayService.Binding binding : relayService.bindingsSnapshot()) {
            if (now - binding.lastActivityMs > idleTimeoutMs) {
                log.info("远程会话空闲超时: sessionId={}", binding.control.getSessionId());
                relayService.closeIdleBinding(binding, "idle-timeout");
            }
        }
    }
}
