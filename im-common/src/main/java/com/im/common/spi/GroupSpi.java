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
}
