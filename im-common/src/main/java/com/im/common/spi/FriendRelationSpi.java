package com.im.common.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 好友关系契约，由 im-friend 模块实现。
 *
 * <p>好友关系为双向双行存储：{@code (userId, friendId)} 与 {@code (friendId, userId)} 各一行，
 * 每行独立维护备注与拉黑状态。
 */
public interface FriendRelationSpi {

    /**
     * a 与 b 是否为正常好友（双向都未拉黑）。
     */
    boolean isFriend(Long a, Long b);

    /**
     * a 是否把 b 拉黑了。
     */
    boolean isBlockedBy(Long a, Long b);

    /**
     * 任意一方拉黑即返回 true，用于消息发送前置校验。
     */
    boolean isBlockedEitherWay(Long a, Long b);

    /**
     * 静默解除 a 对 b 的拉黑：未拉黑时什么也不做，不校验不报错。
     *
     * <p>用于「单向阻断」：A 拉黑 B 后只拦 B→A 的入站消息，A 主动给 B 发消息时
     * 发送成功后自动解除拉黑，不能让发送方因为一次正常发送而收到异常。
     */
    void unblockSilently(Long a, Long b);

    /**
     * 获取 a 对 b 的好友备注。
     *
     * @return 无备注时返回 {@code null}
     */
    String getRemark(Long a, Long b);

    /**
     * 获取某用户的全部正常好友 ID（已排除单向拉黑的关系）。
     *
     * <p>供 im-user 在上下线时定向推送在线状态变更，也供其他模块做面向好友的广播。
     */
    List<Long> listFriendIds(Long userId);

    /**
     * 批量获取 userId 对一批用户的好友备注，避免会话列表逐条回源产生 N+1。
     *
     * @return key 为好友 ID，未设置备注的好友不会出现在结果中
     */
    Map<Long, String> getRemarks(Long userId, Collection<Long> friendIds);
}
