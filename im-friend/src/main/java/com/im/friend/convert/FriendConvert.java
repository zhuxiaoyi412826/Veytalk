package com.im.friend.convert;

import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.RequestStatus;
import com.im.common.util.TextUtil;
import com.im.friend.dto.vo.FriendRequestVO;
import com.im.friend.dto.vo.FriendVO;
import com.im.friend.entity.Friend;
import com.im.friend.entity.FriendRequest;

/**
 * 好友实体到视图对象的转换。
 *
 * <p>刻意保持无状态且不注入任何 SPI：对方资料由 Service 批量查好后作为参数传入，
 * 这样一次列表渲染只需一次 {@code UserQuerySpi.listByIds}，不会产生 N+1，
 * 也避免转换器反向依赖 SPI 造成装配环。
 */
public final class FriendConvert {

    private FriendConvert() {
    }

    /**
     * 组装好友列表项。
     *
     * @param relation 我持有的关系行
     * @param peer     对方精简资料，用户已注销时为 {@code null}
     */
    public static FriendVO toVO(Friend relation, UserBriefDTO peer) {
        String remark = relation.getRemark();
        String nickname = peer == null ? null : peer.getNickname();
        boolean blocked = relation.isBlocked();
        return FriendVO.builder()
                .friendId(relation.getFriendId())
                .username(peer == null ? null : peer.getUsername())
                .nickname(nickname)
                .displayName(displayName(remark, nickname, peer, relation.getFriendId()))
                .avatar(peer == null ? null : peer.getAvatar())
                .gender(peer == null ? null : peer.getGender())
                .signature(peer == null ? null : peer.getSignature())
                .online(peer != null && Boolean.TRUE.equals(peer.getOnline()))
                .remark(remark)
                .groupName(relation.getGroupName())
                .status(relation.getStatus())
                .blocked(blocked)
                .createTime(relation.getCreateTime())
                .build();
    }

    /**
     * 组装好友申请列表项。
     *
     * @param request  申请记录
     * @param peer     对方精简资料
     * @param received {@code true} 表示这是我收到的申请，{@code false} 表示我发出的
     */
    public static FriendRequestVO toVO(FriendRequest request, UserBriefDTO peer, boolean received) {
        boolean pending = request.isPending();
        return FriendRequestVO.builder()
                .id(request.getId())
                .fromUserId(request.getFromUserId())
                .toUserId(request.getToUserId())
                .direction(received ? FriendRequestVO.DIRECTION_RECEIVED : FriendRequestVO.DIRECTION_SENT)
                .peerUserId(received ? request.getFromUserId() : request.getToUserId())
                .peerUsername(peer == null ? null : peer.getUsername())
                .peerNickname(peer == null ? null : peer.getNickname())
                .peerAvatar(peer == null ? null : peer.getAvatar())
                .peerOnline(peer != null && Boolean.TRUE.equals(peer.getOnline()))
                .verifyMessage(request.getVerifyMessage())
                .source(request.getSource())
                .status(request.getStatus())
                .statusDesc(RequestStatus.descOf(request.getStatus()))
                // 只有「我收到且仍待处理」的申请才允许点同意/拒绝
                .actionable(received && pending)
                .handleTime(request.getHandleTime())
                .createTime(request.getCreateTime())
                .build();
    }

    /**
     * 展示名优先级：备注 &gt; 昵称 &gt; 账号 &gt; 兜底文案。
     *
     * <p>对方账号被注销时 {@code peer} 为 null，仍需给出可读名称，避免前端出现空白行。
     */
    private static String displayName(String remark, String nickname, UserBriefDTO peer, Long userId) {
        if (TextUtil.isNotBlank(remark)) {
            return remark;
        }
        if (TextUtil.isNotBlank(nickname)) {
            return nickname;
        }
        if (peer != null && TextUtil.isNotBlank(peer.getUsername())) {
            return peer.getUsername();
        }
        return "用户" + userId;
    }
}
