package com.im.common.spi;

import com.im.common.enums.ProfileVisitType;

/**
 * 访客足迹契约，由 im-friend 实现（{@code im_profile_visit} 归它）。
 *
 * <p>为什么方向是「im-user 调 im-friend」而不是反过来：这张表记的是关系侧的一件事
 * （谁来看过我），而<em>触发</em>它的读入口在资料侧（{@code GET /api/dating/profile/{userId}}）。
 * 让 im-friend 去监听资料卡的读取要么把表搬到 im-user，要么让 im-user 暴露一个回调，
 * 两种都比现在这条线贵。与 {@code DatingQuerySpiImpl} 注 {@code FriendRelationSpi} 是同一个方向。
 *
 * <p><b>「该不该记」不由本契约判断</b>：隐身位（{@code discoverable}）住在 {@code im_user_profile}，
 * 只有调用方读得到它。实现方只负责「记下来」这一件事。
 */
public interface ProfileVisitSpi {

    /**
     * 记一次「visitor 看了 visited」。
     *
     * <p>同一 {@code (visitor, visited, 当天)} 恒为一行，重复调用只累加次数并把最近时间推后，
     * 所以调用方<b>不需要自己去重</b>，页面上反复刷新也不会刷出二十条同名访客。
     *
     * <p>本方法不校验账号状态、不校验拉黑、也不抛业务异常给调用方之外的地方；
     * 它是一次附属写入，调用方应当容忍它失败（资料已经给人看到了，足迹丢了只是少一条记录）。
     *
     * @param visitorId 看的人；与 {@code visitedId} 相同时实现方也会跳过（自己不进自己的访客列表）
     * @param visitedId 被看的人，访客列表按它查
     * @param type      从哪个入口看的，见 {@link ProfileVisitType}
     */
    void record(Long visitorId, Long visitedId, ProfileVisitType type);
}
