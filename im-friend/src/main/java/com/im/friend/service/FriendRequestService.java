package com.im.friend.service;

import com.im.common.api.PageResult;
import com.im.friend.dto.req.FriendApplyRequest;
import com.im.friend.dto.req.FriendRequestQuery;
import com.im.friend.dto.vo.FriendRequestVO;

/**
 * 好友申请服务：发起、查询、同意、拒绝。
 */
public interface FriendRequestService {

    /**
     * 发起好友申请。
     *
     * <p>校验链：目标存在 -&gt; 不是自己 -&gt; 已是好友则拒绝 -&gt; 对方拉黑我则拒绝 -&gt;
     * 我拉黑对方则提示先解除 -&gt; 已有待处理申请则拒绝。
     *
     * @throws com.im.common.exception.BusinessException 校验不通过时抛 3xxx 错误码
     */
    FriendRequestVO apply(Long userId, FriendApplyRequest request);

    /**
     * 我收到的申请，按申请时间倒序分页。
     */
    PageResult<FriendRequestVO> listReceived(Long userId, FriendRequestQuery query);

    /**
     * 我发出的申请，按申请时间倒序分页。
     */
    PageResult<FriendRequestVO> listSent(Long userId, FriendRequestQuery query);

    /**
     * 同意申请：双向建立好友关系 + 更新申请状态 + 创建单聊会话 + 发送系统通知。
     *
     * @param userId    处理人（必须是被申请人）
     * @param requestId 申请 ID
     * @return 新建或复用的单聊会话 ID，im-conversation 未装配时为 {@code null}
     */
    Long accept(Long userId, Long requestId);

    /**
     * 拒绝申请，仅更新申请状态，不建立任何关系。
     */
    void reject(Long userId, Long requestId);

    /**
     * 我收到的待处理申请数，用于前端红点。
     */
    long countPending(Long userId);
}
