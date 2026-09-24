package com.im.user.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.cache.ThreeLevelCache;
import com.im.common.constant.ImConstants;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.user.convert.UserConvert;
import com.im.user.entity.User;
import com.im.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 *
 * <p>静态资料（昵称/头像/签名等）走三级缓存，在线状态永远实时查 Redis：
 * 缓存只存不含 online 的 DTO，返回前用 {@code toBuilder} 复制一份再补在线位，
 * 避免把某一刻的在线状态固化进 L1 共享引用。失效点在 UserServiceImpl 的资料/头像更新处。
 */
@Service
@RequiredArgsConstructor
public class UserQuerySpiImpl implements UserQuerySpi {

    private final UserMapper userMapper;
    private final OnlineStatusSpi onlineStatusSpi;
    private final ThreeLevelCache cache;

    @Override
    public UserBriefDTO getById(Long userId) {
        if (userId == null) {
            return null;
        }
        UserBriefDTO cached = cache.get(cacheKey(userId), UserBriefDTO.class,
                () -> UserConvert.toBrief(userMapper.selectById(userId)));
        return cached == null ? null : cached.toBuilder().online(isOnline(userId)).build();
    }

    @Override
    public Map<Long, UserBriefDTO> listByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, UserBriefDTO> result = new HashMap<>(userIds.size());
        List<Long> misses = new ArrayList<>(userIds.size());
        for (Long id : userIds) {
            if (id == null) {
                continue;
            }
            UserBriefDTO cached = cache.peek(cacheKey(id), UserBriefDTO.class);
            if (cached != null) {
                result.put(id, cached);
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            // 未命中部分一次批量回源，再逐条回填缓存；查不到的也 put null 记哨兵防穿透
            List<User> users = userMapper.selectByIds(misses);
            Map<Long, UserBriefDTO> found = new HashMap<>(users == null ? 0 : users.size());
            if (users != null) {
                for (User user : users) {
                    found.put(user.getId(), UserConvert.toBrief(user));
                }
            }
            for (Long id : misses) {
                UserBriefDTO brief = found.get(id);
                cache.put(cacheKey(id), brief, null);
                if (brief != null) {
                    result.put(id, brief);
                }
            }
        }
        if (result.isEmpty()) {
            return Collections.emptyMap();
        }
        // 一次性算出在线集合，避免逐个用户查 Redis；缓存对象是共享引用，复制后再补在线位
        List<Long> onlineIds = onlineStatusSpi.filterOnline(result.keySet());
        Map<Long, UserBriefDTO> withOnline = new HashMap<>(result.size());
        for (Map.Entry<Long, UserBriefDTO> entry : result.entrySet()) {
            UserBriefDTO brief = entry.getValue();
            withOnline.put(entry.getKey(), brief.toBuilder().online(onlineIds.contains(entry.getKey())).build());
        }
        return withOnline;
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

    private static String cacheKey(Long userId) {
        return ImConstants.CACHE_USER_BRIEF_PREFIX + userId;
    }

    private int safeStatus(User user) {
        return user.getStatus() == null ? User.STATUS_DISABLED : user.getStatus();
    }
}
