package com.im.common.spi;

import com.im.common.domain.GroupBriefDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 群组契约，由 im-group 模块实现。
 */
public interface GroupSpi {

    /**
     * 是否为群成员（状态正常）。
     */
    boolean isMember(Long groupId, Long userId);

    /**
     * 获取我在群内的角色。
     *
     * @return 1 群主 2 管理员 3 成员；非成员返回 {@code null}
     */
    Integer getRole(Long groupId, Long userId);

    /**
     * 是否处于禁言状态：单人禁言未到期，或全员禁言且自己不是群主/管理员。
     */
    boolean isMuted(Long groupId, Long userId);

    /**
     * 获取全部正常状态的成员 ID。
     */
    List<Long> getMemberIds(Long groupId);

    /**
     * 获取群精简信息。
     *
     * @param viewerId 用于回填 myRole / myNickname，可为空
     * @return 群不存在时返回 {@code null}
     */
    GroupBriefDTO getBrief(Long groupId, Long viewerId);

    /**
     * 批量获取群名称，用于会话列表展示。
     *
     * @return key 为 groupId
     */
    Map<Long, GroupBriefDTO> listBriefs(Collection<Long> groupIds);

    /**
     * 群是否存在且未解散。
     */
    boolean exists(Long groupId);

    /**
     * 某人当前在群里的全部群 ID（成员状态正常）。
     *
     * <p>交友侧算「你们有多少个共同群」用：拿两边的 ID 集合求交，
     * 比逐群问一次 {@link #isMember} 少几百次查询。
     *
     * <p>已退的与已解散的群不在结果里（解散时成员行会被整批置为退群）：
     * 它们已经不是一段还能发生来往的关系，算进共同群只会让数字虚高。
     *
     * @return 没加过任何群时返回空列表，不返回 {@code null}
     */
    List<Long> listJoinedGroupIds(Long userId);
}
