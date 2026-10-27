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

    /**
     * 按昵称或账号<b>模糊</b>反查用户 ID，给跨模块的后台检索把「名字」变成「ID 集合」。
     *
     * <p>存在的理由：后台的检索框里管理员拿到的往往是花名而不是 ID，而资料在
     * {@code im_user}、待筛的行在别的表（群成员、钱包账本），星型架构下对方读不到用户表，
     * 就只能由本契约把这一步代办掉。返回的是 ID 而不是 {@code UserBriefDTO}：
     * 调用方要的是把名字拼进自己的查询条件，昵称它自己会再补一次。
     *
     * <p>{@code nickname LIKE '%kw%'} 用不上索引（账号列有唯一键，前缀匹配才用得上），
     * 所以 {@code limit} 是必需的，调用方不要指望拿到「全部命中行」。同一批人反复撞名时，
     * 取的是 ID 大的那些（新注册在前）。
     *
     * @param keyword 关键词，空白视为无意义调用
     * @param limit   返回的 ID 个数上限，实现方自己再夹一层
     * @return 命中的用户 ID，无命中返回空集合，<b>不返回 null</b>
     */
    List<Long> searchUserIds(String keyword, int limit);
}
