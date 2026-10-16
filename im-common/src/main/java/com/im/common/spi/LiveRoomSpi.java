package com.im.common.spi;

/**
 * 直播房间上下文契约，由 im-live 模块实现。
 *
 * <p>存在的理由与 {@link FriendRelationSpi} 同源：连麦信令服务（{@code LiveLinkSignalingService}）位于
 * im-websocket，而 im-websocket 与 im-live 是星型架构里两个互不依赖的模块（都只依赖 im-common）。
 * 信令侧要判断「房间是否还在直播、主播是谁、被邀者是不是本场观众」，不能直接引 im-live 的 Service/Mapper，
 * 于是把这几问收敛成一个 SPI：im-websocket 只依赖接口，im-live 提供实现，装配在同一条 Spring 上下文里完成。
 *
 * <p>与 {@code CallSignalingService} 用 {@code FriendRelationSpi} 判好友一样，这里用 {@code ObjectProvider}
 * 消费：直播模块缺席时连麦本就无从谈起，实现方直接返回「不可直播/无主播」，由信令侧据此拒绝上麦。
 */
public interface LiveRoomSpi {

    /**
     * 房间是否存在且正处于直播中（{@code status == LIVING}）。
     *
     * <p>已结束 / 已封禁 / 不存在都返回 {@code false}：连麦只在直播进行中才成立。
     */
    boolean isLiving(Long roomId);

    /**
     * 取房间的主播用户 ID。
     *
     * @return 房间不存在时返回 {@code null}
     */
    Long anchorIdOf(Long roomId);

    /**
     * 该用户当前是否为这房间的活跃观众（已连上本房间的弹幕长连接）。
     *
     * <p>连麦把通话的「必须好友」校验放宽为「被邀者是本场观众」：只有正在这间房里的人才有资格申请上麦 /
     * 被主播邀请，避免对任意 userId 推连麦来电。主播本人也应能通过（用 {@code anchorIdOf} 单独判即可）。
     */
    boolean isActiveViewer(Long roomId, Long userId);

    /**
     * 广播连麦态变更（有嘉宾上麦 / 全部下麦）给房间内观众。
     *
     * <p>连麦开播后主播端把「主播+嘉宾」合成推流成 WebM，观众需从 HLS 播放器切到 MSE/WebM 播放器；
     * 关麦后切回。信令侧（im-websocket）不直接掌握 {@code /ws/live} 广播能力，经此回调交给 im-live 扇出，
     * 保持星型架构下 im-websocket 不反向依赖 im-live。默认空实现：直播模块缺席时该信令无处可发，静默即可。
     *
     * @param roomId 房间 ID
     * @param active {@code true} 表示当前至少一名嘉宾在麦（合成流生效），{@code false} 表示已无人在麦
     */
    default void publishLinkmic(Long roomId, boolean active) {
        // 默认不做任何事：由 im-live 的实现覆写为经弹幕通道广播
    }
}
