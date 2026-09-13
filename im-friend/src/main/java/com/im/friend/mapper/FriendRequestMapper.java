package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.enums.RequestStatus;
import com.im.friend.entity.FriendRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 好友申请 Mapper。
 */
@Mapper
public interface FriendRequestMapper extends BaseMapper<FriendRequest> {

    /**
     * 查询 {@code from -> to} 方向上仍处于待处理状态的申请。
     *
     * <p>唯一键 {@code uk_from_to_pending} 保证最多一条，这里仍加 {@code LIMIT 1} 兜底。
     *
     * @return 不存在时返回 {@code null}
     */
    default FriendRequest selectPending(Long fromUserId, Long toUserId) {
        return selectOne(Wrappers.<FriendRequest>lambdaQuery()
                .eq(FriendRequest::getFromUserId, fromUserId)
                .eq(FriendRequest::getToUserId, toUserId)
                .eq(FriendRequest::getStatus, RequestStatus.PENDING.getCode())
                .last("LIMIT 1"));
    }

    /**
     * 统计某人收到的待处理申请数，用于前端红点。
     */
    default long countPending(Long toUserId) {
        Long count = selectCount(Wrappers.<FriendRequest>lambdaQuery()
                .eq(FriendRequest::getToUserId, toUserId)
                .eq(FriendRequest::getStatus, RequestStatus.PENDING.getCode()));
        return count == null ? 0L : count;
    }

    /**
     * 查询待处理申请并校验处理人身份，避免越权处理他人申请。
     *
     * @param requestId 申请 ID
     * @param toUserId  被申请人（只有被申请人才有权同意 / 拒绝）
     * @return 不存在或不属于该处理人时返回 {@code null}
     */
    default FriendRequest selectPendingForHandler(Long requestId, Long toUserId) {
        return selectOne(Wrappers.<FriendRequest>lambdaQuery()
                .eq(FriendRequest::getId, requestId)
                .eq(FriendRequest::getToUserId, toUserId)
                .eq(FriendRequest::getStatus, RequestStatus.PENDING.getCode())
                .last("LIMIT 1"));
    }
}
