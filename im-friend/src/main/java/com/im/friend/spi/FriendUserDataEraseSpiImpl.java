package com.im.friend.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.spi.UserDataEraseSpi;
import com.im.friend.entity.Friend;
import com.im.friend.entity.FriendRequest;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.mapper.FriendRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账号注销时清除 im-friend 持有的用户数据：
 * 好友关系两行（我持有的 + 别人持有的对我这一行）与全部好友申请记录都物理删除。
 *
 * <p>本表无 {@code deleted} 列，物理删除即彻底清除，符合个保法/GDPR 的删除请求语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendUserDataEraseSpiImpl implements UserDataEraseSpi {

    private final FriendMapper friendMapper;
    private final FriendRequestMapper friendRequestMapper;

    @Override
    public void eraseUserData(Long userId) {
        if (userId == null) {
            return;
        }
        int relations = friendMapper.delete(Wrappers.<Friend>lambdaQuery()
                .and(w -> w.eq(Friend::getUserId, userId).or().eq(Friend::getFriendId, userId)));
        int requests = friendRequestMapper.delete(Wrappers.<FriendRequest>lambdaQuery()
                .and(w -> w.eq(FriendRequest::getFromUserId, userId).or().eq(FriendRequest::getToUserId, userId)));
        log.info("[账号注销] im-friend 清除 userId={}：好友关系 {} 行、好友申请 {} 行", userId, relations, requests);
    }
}
