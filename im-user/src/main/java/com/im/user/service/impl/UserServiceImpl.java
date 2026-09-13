package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.exception.BusinessException;
import com.im.common.security.PasswordEncryptor;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.spi.PermissionProvider;
import com.im.common.util.TextUtil;
import com.im.user.convert.UserConvert;
import com.im.user.dto.req.ChangePasswordRequest;
import com.im.user.dto.req.UpdateProfileRequest;
import com.im.user.dto.req.UserSearchQuery;
import com.im.user.dto.vo.UserCardVO;
import com.im.user.dto.vo.UserVO;
import com.im.user.entity.User;
import com.im.user.entity.UserRole;
import com.im.user.mapper.RoleMapper;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserRoleMapper;
import com.im.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户领域服务实现。
 *
 * <p>好友关系字段通过 {@link FriendRelationSpi} 按需注入：im-friend 未装配时（例如只跑用户模块的测试）
 * 卡片仍能正常返回，只是关系标记全部为 false。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncryptor passwordEncryptor;
    private final OnlineStatusSpi onlineStatusSpi;
    private final PermissionProvider permissionProvider;
    private final ObjectProvider<FriendRelationSpi> friendRelationSpiProvider;

    @Override
    public User requireById(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Override
    public User findByUsername(String username) {
        if (TextUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim())
                .last("LIMIT 1"));
    }

    @Override
    public User findByPhone(String phone) {
        if (TextUtil.isBlank(phone)) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, phone.trim())
                .last("LIMIT 1"));
    }

    @Override
    public User findByAccount(String account) {
        if (TextUtil.isBlank(account)) {
            return null;
        }
        String value = account.trim();
        User user = findByUsername(value);
        return user != null ? user : findByPhone(value);
    }

    @Override
    public boolean existsUsername(String username) {
        return TextUtil.isNotBlank(username) && userMapper.exists(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim()));
    }

    @Override
    public boolean existsPhone(String phone) {
        return TextUtil.isNotBlank(phone) && userMapper.exists(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, phone.trim()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User createUser(String username, String rawPassword, String nickname, String phone, String email) {
        String account = username == null ? null : username.trim();
        if (TextUtil.isBlank(account)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账号不能为空");
        }
        BusinessException.throwIf(existsUsername(account), ResultCode.USER_ALREADY_EXISTS);
        BusinessException.throwIf(existsPhone(phone), ResultCode.USER_PHONE_EXISTS);

        User user = new User();
        user.setUsername(account);
        user.setPassword(passwordEncryptor.encode(rawPassword));
        user.setNickname(TextUtil.isBlank(nickname) ? account : nickname.trim());
        user.setPhone(TextUtil.isBlank(phone) ? null : phone.trim());
        user.setEmail(TextUtil.isBlank(email) ? null : email.trim());
        user.setGender(User.GENDER_UNKNOWN);
        user.setStatus(User.STATUS_NORMAL);
        userMapper.insert(user);

        bindDefaultRole(user.getId());
        log.info("新用户注册成功: userId={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    /**
     * 绑定注册默认角色；种子数据缺失时只告警不阻断注册。
     */
    private void bindDefaultRole(Long userId) {
        Long roleId = roleMapper.selectIdByRoleCode(ImConstants.ROLE_USER);
        if (roleId == null) {
            log.warn("未找到默认角色 {}，请确认已执行 sql/im_data.sql", ImConstants.ROLE_USER);
            return;
        }
        userRoleMapper.insert(new UserRole(userId, roleId));
    }

    @Override
    public UserVO getProfile(Long userId) {
        UserVO vo = UserConvert.toVO(requireById(userId));
        vo.setOnline(isOnline(userId));
        vo.setRoles(listRoles(userId));
        vo.setPermissions(listPermissions(userId));
        return vo;
    }

    @Override
    public UserVO updateProfile(Long userId, UpdateProfileRequest request) {
        requireById(userId);
        User patch = new User();
        patch.setId(userId);
        boolean changed = false;

        if (TextUtil.isNotBlank(request.getNickname())) {
            patch.setNickname(request.getNickname().trim());
            changed = true;
        }
        if (request.getAvatar() != null) {
            patch.setAvatar(TextUtil.sanitize(request.getAvatar(), 512));
            changed = true;
        }
        if (request.getGender() != null) {
            patch.setGender(request.getGender());
            changed = true;
        }
        if (request.getSignature() != null) {
            patch.setSignature(TextUtil.sanitize(request.getSignature(), 255));
            changed = true;
        }
        if (request.getEmail() != null) {
            patch.setEmail(request.getEmail().trim());
            changed = true;
        }
        if (!changed) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有需要修改的字段");
        }
        userMapper.updateById(patch);
        return getProfile(userId);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requireById(userId);
        if (!passwordEncryptor.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.USER_OLD_PASSWORD_ERROR);
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        User patch = new User();
        patch.setId(userId);
        patch.setPassword(passwordEncryptor.encode(request.getNewPassword()));
        userMapper.updateById(patch);
        log.info("用户 {} 修改密码成功", userId);
    }

    @Override
    public void updateAvatar(Long userId, String avatarUrl) {
        User patch = new User();
        patch.setId(userId);
        patch.setAvatar(avatarUrl);
        userMapper.updateById(patch);
    }

    @Override
    public void touchLogin(Long userId, String ip) {
        User patch = new User();
        patch.setId(userId);
        patch.setLastLoginTime(LocalDateTime.now());
        patch.setLastLoginIp(ip);
        userMapper.updateById(patch);
    }

    @Override
    public PageResult<UserCardVO> search(UserSearchQuery query, Long currentUserId) {
        Page<User> page = query.toPage();
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim();
        if (keyword.isEmpty()) {
            return PageResult.empty(page.getCurrent(), page.getSize());
        }

        LambdaQueryWrapper<User> wrapper = Wrappers.<User>lambdaQuery()
                .eq(User::getStatus, User.STATUS_NORMAL);
        wrapper.and(w -> {
            w.like(User::getUsername, keyword).or().like(User::getNickname, keyword);
            // 精确模式下手机号走全等，避免模糊匹配泄露号段
            if (query.isExact()) {
                w.or().eq(User::getPhone, keyword);
            } else {
                w.or().like(User::getPhone, keyword);
            }
        });
        if (currentUserId != null) {
            wrapper.ne(User::getId, currentUserId);
        }
        wrapper.orderByDesc(User::getId);
        userMapper.selectPage(page, wrapper);

        List<User> records = page.getRecords();
        Set<Long> onlineIds = records.isEmpty()
                ? Collections.emptySet()
                : new HashSet<>(onlineStatusSpi.filterOnline(records.stream().map(User::getId).toList()));
        List<UserCardVO> cards = records.stream()
                .map(user -> buildCard(user, onlineIds.contains(user.getId()), currentUserId))
                .toList();
        return PageResult.of(cards, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public UserCardVO getCard(Long targetUserId, Long currentUserId) {
        User target = requireById(targetUserId);
        return buildCard(target, isOnline(targetUserId), currentUserId);
    }

    /**
     * 组装资料卡片并补齐好友关系。
     */
    private UserCardVO buildCard(User user, boolean online, Long currentUserId) {
        UserCardVO card = UserConvert.toCard(user);
        card.setOnline(online);
        FriendRelationSpi friendSpi = friendRelationSpiProvider.getIfAvailable();
        if (friendSpi == null || currentUserId == null || currentUserId.equals(user.getId())) {
            return card;
        }
        card.setFriend(friendSpi.isFriend(currentUserId, user.getId()));
        card.setRemark(friendSpi.getRemark(currentUserId, user.getId()));
        card.setBlocked(friendSpi.isBlockedBy(currentUserId, user.getId()));
        card.setBlockedByOther(friendSpi.isBlockedBy(user.getId(), currentUserId));
        return card;
    }

    @Override
    public Map<Long, Boolean> batchOnline(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> online = new HashSet<>(onlineStatusSpi.filterOnline(userIds));
        Map<Long, Boolean> result = new LinkedHashMap<>(userIds.size());
        for (Long userId : userIds) {
            if (userId != null) {
                result.put(userId, online.contains(userId));
            }
        }
        return result;
    }

    @Override
    public List<String> listRoles(Long userId) {
        return permissionProvider.getRoleList(userId, ImConstants.LOGIN_TYPE);
    }

    @Override
    public List<String> listPermissions(Long userId) {
        return permissionProvider.getPermissionList(userId, ImConstants.LOGIN_TYPE);
    }

    private boolean isOnline(Long userId) {
        return !onlineStatusSpi.getOnlineDevices(userId).isEmpty();
    }
}
