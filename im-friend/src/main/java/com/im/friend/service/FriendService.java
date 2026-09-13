package com.im.friend.service;

import com.im.friend.dto.vo.FriendVO;
import com.im.friend.entity.Friend;

import java.util.List;

/**
 * 好友关系服务：列表、备注、分组、删除、拉黑。
 */
public interface FriendService {

    /**
     * 好友列表，含对方资料、在线状态与我的备注 / 分组 / 拉黑标记。
     *
     * @param userId  当前用户
     * @param keyword 可选，按备注、昵称、账号模糊过滤
     */
    List<FriendVO> list(Long userId, String keyword);

    /**
     * 当前用户的全部好友分组名，「默认分组」置顶，其余按字典序。
     */
    List<String> listGroups(Long userId);

    /**
     * 好友视角的资料卡片。
     *
     * @throws com.im.common.exception.BusinessException 不是好友时抛 {@code FRIEND_NOT_FOUND}
     */
    FriendVO detail(Long userId, Long friendId);

    /**
     * 修改备注，传空表示清除备注。
     */
    void updateRemark(Long userId, Long friendId, String remark);

    /**
     * 移动好友到指定分组。
     */
    void updateGroup(Long userId, Long friendId, String groupName);

    /**
     * 删除好友：双向物理删除两行关系。
     *
     * <p>会话与历史消息刻意保留，只解除关系；用户若不想再看到该会话，
     * 可在会话列表侧单独「删除会话」做本端隐藏，避免误删聊天记录。
     */
    void delete(Long userId, Long friendId);

    /**
     * 拉黑好友，只修改我持有的那一行，对方视角不受影响。
     */
    void block(Long userId, Long friendId);

    /**
     * 取消拉黑。
     */
    void unblock(Long userId, Long friendId);

    /**
     * 建立双向好友关系，供同意申请时调用；已存在的残留行会被恢复为正常状态。
     */
    void bindRelation(Long userA, Long userB);

    /**
     * 取出我持有的关系行，不存在即抛 {@code FRIEND_NOT_FOUND}。
     */
    Friend requireRelation(Long userId, Long friendId);
}
