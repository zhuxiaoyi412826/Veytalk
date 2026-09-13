package com.im.user.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.im.common.api.ResultCode;
import com.im.common.domain.WsPacket;
import com.im.common.enums.DeviceType;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.security.PasswordEncryptor;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.spi.PushSpi;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.user.dto.req.LoginRequest;
import com.im.user.dto.req.RegisterRequest;
import com.im.user.dto.req.SmsLoginRequest;
import com.im.user.dto.vo.LoginVO;
import com.im.user.dto.vo.UserVO;
import com.im.user.entity.User;
import com.im.user.service.AuthService;
import com.im.user.service.CaptchaService;
import com.im.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证服务实现。
 *
 * <p>登录态由 Sa-Token 的 JWT 混合模式承载：token 自身可离线验签，同时 Redis 保留会话索引，
 * 因此既支持无状态校验，也保留了踢人下线与多端管理的能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final CaptchaService captchaService;
    private final PasswordEncryptor passwordEncryptor;
    private final OnlineStatusSpi onlineStatusSpi;
    private final ObjectProvider<PushSpi> pushSpiProvider;
    private final ObjectProvider<FriendRelationSpi> friendRelationSpiProvider;

    @Override
    public LoginVO login(LoginRequest request) {
        captchaService.verifyImage(request.getCaptchaKey(), request.getCaptchaCode());

        String account = request.getAccount() == null ? null : request.getAccount().trim();
        User user = request.byPhone() ? userService.findByPhone(account) : userService.findByAccount(account);
        // 账号不存在与密码错误返回同一个错误码，避免被用来枚举有效账号
        if (user == null || !passwordEncryptor.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败: account={}, ip={}", account, SecurityUtil.getClientIp());
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }
        checkUsable(user);
        return doLogin(user, request.getDeviceId());
    }

    @Override
    public LoginVO loginBySms(SmsLoginRequest request) {
        captchaService.verifySms(request.getPhone(), request.getSmsCode());

        User user = userService.findByPhone(request.getPhone());
        if (user == null) {
            user = autoRegister(request.getPhone());
        }
        checkUsable(user);
        return doLogin(user, request.getDeviceId());
    }

    @Override
    public LoginVO register(RegisterRequest request) {
        captchaService.verifyImage(request.getCaptchaKey(), request.getCaptchaCode());
        User user = userService.createUser(request.getUsername(), request.getPassword(),
                request.getNickname(), request.getPhone(), request.getEmail());
        return doLogin(user, null);
    }

    @Override
    public void logout() {
        if (!StpUtil.isLogin()) {
            return;
        }
        Long userId = SecurityUtil.getUserIdOrNull();
        String device = DeviceType.codeOf(StpUtil.getLoginDeviceType());
        StpUtil.logout();
        // 监听器可能因取不到设备信息而清理了全部在线状态，这里按设备精确回填一次
        if (userId != null) {
            onlineStatusSpi.setOffline(userId, device);
            notifyOnlineState(userId, false);
        }
    }

    @Override
    public void logoutEverywhere(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            StpUtil.logout(userId);
        } catch (Exception e) {
            log.warn("批量注销用户 {} 的登录态失败: {}", userId, e.getMessage());
        }
        onlineStatusSpi.clear(userId);
        notifyOnlineState(userId, false);
    }

    @Override
    public LoginVO refresh() {
        Long userId = SecurityUtil.getUserId();
        User user = userService.requireById(userId);
        checkUsable(user);

        String oldToken = StpUtil.getTokenValue();
        String device = DeviceType.codeOf(StpUtil.getLoginDeviceType());
        StpUtil.login(userId, StpUtil.createSaLoginParameter().setDeviceType(device));
        SaTokenInfo info = StpUtil.getTokenInfo();
        if (oldToken != null && !oldToken.equals(info.getTokenValue())) {
            StpUtil.logoutByTokenValue(oldToken);
        }
        // 旧 token 注销会触发监听器清理在线状态，这里重新标记当前设备在线
        onlineStatusSpi.setOnline(userId, device);
        return buildLoginVO(info, device, userService.getProfile(userId));
    }

    @Override
    public UserVO currentUser() {
        return userService.getProfile(SecurityUtil.getUserId());
    }

    /**
     * 手机号首次短信登录时自动建号：账号取 {@code u + 手机号}，密码为不可知的随机值，
     * 用户后续可在个人中心自行设置密码。
     */
    private User autoRegister(String phone) {
        String username = "u" + phone;
        int attempt = 0;
        while (userService.existsUsername(username) && attempt < 5) {
            attempt++;
            username = "u" + phone + attempt;
        }
        String nickname = "用户" + phone.substring(phone.length() - 4);
        log.info("手机号 {} 未注册，自动创建账号 {}", TextUtil.maskPhone(phone), username);
        return userService.createUser(username, UUID.randomUUID().toString(), nickname, phone, null);
    }

    /**
     * 完成 Sa-Token 登录并组装返回体。
     */
    private LoginVO doLogin(User user, String deviceId) {
        String device = DeviceType.codeOf(deviceId);
        kickSameDevice(user.getId(), device);

        StpUtil.login(user.getId(), StpUtil.createSaLoginParameter().setDeviceType(device));
        userService.touchLogin(user.getId(), SecurityUtil.getClientIp());
        notifyOnlineState(user.getId(), true);
        return buildLoginVO(StpUtil.getTokenInfo(), device, userService.getProfile(user.getId()));
    }

    /**
     * 同设备重复登录时顶掉旧连接：先推 kickout 报文让客户端收到明确原因，再注销服务端会话。
     */
    private void kickSameDevice(Long userId, String device) {
        List<String> tokens = StpUtil.getTokenValueListByLoginId(userId, device);
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi != null) {
            pushSpi.kickOut(userId, device, ResultCode.USER_KICKED_OUT.getMessage());
        }
        StpUtil.kickout(userId, device);
        log.info("用户 {} 在设备 {} 上的 {} 个旧会话已被顶下线", userId, device, tokens.size());
    }

    private LoginVO buildLoginVO(SaTokenInfo info, String device, UserVO userInfo) {
        return LoginVO.builder()
                .token(info.getTokenValue())
                .tokenName(info.getTokenName())
                .expiresIn(info.getTokenTimeout())
                .deviceType(device)
                .userInfo(userInfo)
                .build();
    }

    /**
     * 向全部在线好友推送自己的上下线状态，好友列表上的绿点据此实时刷新。
     *
     * <p>im-friend 或 im-websocket 未装配时静默跳过，不影响登录主流程。
     */
    private void notifyOnlineState(Long userId, boolean online) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        FriendRelationSpi friendSpi = friendRelationSpiProvider.getIfAvailable();
        if (pushSpi == null || friendSpi == null) {
            return;
        }
        try {
            List<Long> friendIds = friendSpi.listFriendIds(userId);
            if (friendIds.isEmpty()) {
                return;
            }
            WsPacket packet = WsPacket.of(WsMessageType.ONLINE_STATE, Map.of("userId", userId, "online", online));
            pushSpi.pushToUsers(friendIds, userId, packet);
        } catch (Exception e) {
            log.debug("推送在线状态变更失败: userId={}, {}", userId, e.getMessage());
        }
    }

    private void checkUsable(User user) {
        if (user.getStatus() == null || user.getStatus() != User.STATUS_NORMAL) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }
    }
}
