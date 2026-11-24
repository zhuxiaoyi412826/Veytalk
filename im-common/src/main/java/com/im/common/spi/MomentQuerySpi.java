package com.im.common.spi;

import com.im.common.domain.MomentCardDTO;

/**
 * 动态查询契约，由 im-friend 实现，供 im-message 的「动态转聊天」反查卡片快照。
 *
 * <p>为什么必须有这条契约：{@code MsgType#MOMENT} 的消息在<b>落库前</b>就要确认发送者看得到
 * 那条动态，并按服务端数据覆写 {@code extra} 里的三个快照字段。判定所需的四样东西
 * （单条 {@code visibility}、{@code im_moment_visible} 白名单、作者关系、作者的「几天可见」偏好）
 * 分散在 im-friend 与 im-user 两个模块，im-message 一样也读不到。
 *
 * <p>与 {@link DatingQuerySpi} 刻意分开：那条契约由 <b>im-user</b> 实现、答的是「资料」，
 * 而动态表归 <b>im-friend</b>。把方法挂到 {@code DatingQuerySpi} 上会让 im-user 去查
 * 一张不属于它的表，或者让一个接口有两个实现方——两者都是星型架构里最难排查的那类错。
 *
 * <p>调用方要用 {@code ObjectProvider} 弱依赖注入：im-friend 未装配时消息模块仍要能启动，
 * 只是发不出动态卡片这一种类型。
 */
public interface MomentQuerySpi {

    /**
     * 取一条动态的卡片快照，同时完成可见性判定。
     *
     * <p>判定口径与动态详情接口<b>完全一致</b>（含「三天可见」的时间下界）：
     * 一张卡片如果能在会话里发出去，点进去就必须能打开，否则用户看到的是一张永远报错的死卡。
     *
     * @param momentId 动态 ID
     * @param viewerId 查看者，即这条消息的发送者
     * @return 快照；动态不存在、已下架、或查看者无权看到时返回 {@code null}（不区分这三种）
     */
    MomentCardDTO loadCard(Long momentId, Long viewerId);
}
