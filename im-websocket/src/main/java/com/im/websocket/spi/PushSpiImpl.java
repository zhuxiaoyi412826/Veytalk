package com.im.websocket.spi;

import com.im.common.domain.WsPacket;
import com.im.common.spi.PushSpi;
import com.im.websocket.manager.WsSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * {@link PushSpi} 的实现，把跨模块推送契约适配到本进程的连接注册表。
 *
 * <p>这一层看似只是纯转发，但它的存在是模块解耦的关键：im-message 发消息时调的是
 * {@code PushSpi}，它不知道也不关心 WebSocket 会话怎么存、怎么并发写、失败了怎么清理。
 * 反过来 im-websocket 也不需要知道消息业务，它只提供「把这个报文送到这个人」的能力。
 *
 * <h2>为什么不在这里做「不在线就落离线表」</h2>
 *
 * <p>{@code PushSpi} 的 javadoc 里写了「目标不在线时静默失败，上线后由离线拉取补齐」，
 * 这个补齐不依赖任何额外的离线表：{@code im_message} 本身就是持久化队列，
 * 配合 {@code im_conversation_member.last_ack_seq} 就能算出「哪些消息他还没拉过」。
 * 再建一张离线表等于同一份数据存两处，两处就得保证一致——
 * 而推送失败与写离线表这两件事无法放进同一个事务（推送在事务提交后才做），
 * 一致性根本保证不了。
 *
 * <h2>为什么 kickOut 里不注销登录态</h2>
 *
 * <p>{@code AuthServiceImpl.kickSameDevice} 的调用顺序是「先 {@code pushSpi.kickOut} 推报文，
 * 再 {@code StpUtil.kickout} 注销会话」。这个顺序不能反：注销之后再推，
 * 那条连接对应的登录态已经没了，但 socket 还开着，前端收到 kickout 后尝试重连，
 * 换票据时发现 token 已失效，用户看到的是「被踢下线 → 重连失败 → 回到登录页」，
 * 中间的 kickout 报文反而是唯一能告诉他「你是被顶号了，不是网断了」的信息。
 * 既然注销由调用方负责，这里再调一次 {@code StpUtil.kickout} 就会让同一次顶号产生两轮踢出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushSpiImpl implements PushSpi {

    private final WsSessionManager sessionManager;

    @Override
    public boolean pushToUser(Long userId, WsPacket packet) {
        return sessionManager.pushToUser(userId, packet);
    }

    @Override
    public boolean pushToDevice(Long userId, String deviceId, WsPacket packet) {
        return sessionManager.pushToDevice(userId, deviceId, packet);
    }

    @Override
    public void pushToUsers(Collection<Long> userIds, Long excludeUserId, WsPacket packet) {
        sessionManager.pushToUsers(userIds, excludeUserId, packet);
    }

    @Override
    public void broadcast(WsPacket packet) {
        sessionManager.broadcast(packet);
    }

    @Override
    public void kickOut(Long userId, String deviceId, String reason) {
        sessionManager.kickOut(userId, deviceId, reason);
    }

    /**
     * 用户当前是否有活跃连接。
     *
     * <p>只查本进程注册表，不查 Redis，与 {@code UserQuerySpi.isOnline} 的语义刻意不同：
     * 后者答的是「这个用户最近有没有心跳」（跨实例可见，供好友列表展示在线状态），
     * 这里答的是「我现在能不能把报文送到他手上」。
     *
     * <p>这个区别在多实例部署下是生死攸关的：用户连在另一个节点上时，
     * 这里必须返回 {@code false}，调用方才会走离线补偿；若这里去查 Redis 并返回 {@code true}，
     * 消息就会被推进一条本进程根本不存在的连接，既没送到、又因为「已推送成功」而不进离线队列。
     * 当前是单实例部署，两种查法结果一致，但语义必须先摆正，扩容时才不用回头改。
     */
    @Override
    public boolean isUserOnline(Long userId) {
        return sessionManager.isUserOnline(userId);
    }
}
