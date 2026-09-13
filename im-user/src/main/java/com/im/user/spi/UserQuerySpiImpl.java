package com.im.user.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.user.convert.UserConvert;
import com.im.user.entity.User;
import com.im.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户查询契约实现，供 im-friend / im-conversation / im-message / im-group 等模块补齐资料。
 *
 * <p>约定：{@link #getById} 对「存在但被禁用」的用户同样返回精简信息——历史消息里的发送者昵称
 * 不该因为账号被封而变成空白；而 {@link #exists} 用于业务准入判断，要求状态必须正常。
 */
@Service
@RequiredArgsConstructor
public class UserQuerySpiImpl implements UserQuerySpi {

    private final UserMapper userMapper;
    private final OnlineStatusSpi onlineStatusSpi;

    @Override
    public UserBriefDTO getById(Long userId) {
        if (userId == null) {
            return null;
        }
        return withOnline(userMapper.selectById(userId));
    }

    @Override
    public Map<Long, UserBriefDTO> listByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectByIds(userIds);
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }
        // 一次性算出在线集合，避免逐个用户查 Redis
        List<Long> ids = users.stream().map(User::getId).toList();
        List<Long> onlineIds = onlineStatusSpi.filterOnline(ids);
        Map<Long, UserBriefDTO> result = new HashMap<>(users.size());
        for (User user : users) {
            UserBriefDTO brief = UserConvert.toBrief(user);
            brief.setOnline(onlineIds.contains(user.getId()));
            result.put(user.getId(), brief);
        }
        return result;
    }

    @Override
    public boolean exists(Long userId) {
        if (userId == null) {
            return false;
        }
        User user = userMapper.selectById(userId);
        return user != null && User.STATUS_NORMAL == safeStatus(user);
    }

    @Override
    public boolean isOnline(Long userId) {
        return userId != null && !onlineStatusSpi.getOnlineDevices(userId).isEmpty();
    }

    @Override
    public List<Long> filterOnline(Collection<Long> userIds) {
        return onlineStatusSpi.filterOnline(userIds);
    }

    @Override
    public UserBriefDTO findByAccount(String account) {
        if (TextUtil.isBlank(account)) {
            return null;
        }
        // 用 and(...) 显式包一层括号，避免 OR 与逻辑删除条件 deleted=0 串在一起造成越权命中
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .and(w -> w.eq(User::getUsername, account).or().eq(User::getPhone, account))
                .last("LIMIT 1"));
        return withOnline(user);
    }

    private UserBriefDTO withOnline(User user) {
        UserBriefDTO brief = UserConvert.toBrief(user);
        if (brief != null) {
            brief.setOnline(isOnline(brief.getUserId()));
        }
        return brief;
    }

    private int safeStatus(User user) {
        return user.getStatus() == null ? User.STATUS_DISABLED : user.getStatus();
    }
}
