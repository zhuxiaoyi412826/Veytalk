package com.im.common.spi;

import com.im.common.domain.UserBriefDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户查询契约，由 im-user 模块实现，供其他模块获取用户资料与在线状态。
 */
public interface UserQuerySpi {

    /**
     * 按 ID 查询用户精简信息。
     *
     * @return 用户不存在时返回 {@code null}
     */
    UserBriefDTO getById(Long userId);

    /**
     * 批量查询用户精简信息，避免调用方产生 N+1 查询。
     *
     * @return key 为 userId，不存在的 ID 不会出现在结果中
     */
    Map<Long, UserBriefDTO> listByIds(Collection<Long> userIds);

    /**
     * 用户是否存在且状态正常。
     */
    boolean exists(Long userId);

    /**
     * 用户当前是否在线。
     */
    boolean isOnline(Long userId);

    /**
     * 从给定集合中筛选出在线的用户 ID。
     */
    List<Long> filterOnline(Collection<Long> userIds);

    /**
     * 按账号 / 昵称 / 手机号精确查找用户，用于好友申请与资料卡片。
     *
     * @return 未找到时返回 {@code null}
     */
    UserBriefDTO findByAccount(String account);
}
