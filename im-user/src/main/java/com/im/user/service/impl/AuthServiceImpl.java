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
import com.im.user.dto.req.EmailLoginRequest;
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
        String account = request.getAccount() == null ? null : request.getAccount().trim();
        User user = request.byPhone() ? userService.findByPhone(account) : userService.findByAccount(account);
        // 账号登录不自动注册：账号不存在直接提示“无账号”，引导用户去注册或用验证码登录
        if (user == null) {
            log.warn("登录失败，账号不存在: account={}, ip={}", account, SecurityUtil.getClientIp());
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 验证码登录自动建号的账号密码为哨兵，无法用密码登录，引导先去设置密码
        if (User.NO_PASSWORD.equals(user.getPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_NOT_SET);
        }
        if (!passwordEncryptor.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败，密码错误: account={}, ip={}", account, SecurityUtil.getClientIp());
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
            user = autoRegisterByPhone(request.getPhone());
        }
        checkUsable(user);
        return doLogin(user, request.getDeviceId());
    }

    @Override
    public LoginVO loginByEmail(EmailLoginRequest request) {
        String email = request.getEmail() == null ? null : request.getEmail().trim();
        captchaService.verifyEmail(email, request.getEmailCode());

        User user = userService.findByEmail(email);
        if (user == null) {
            user = autoRegisterByEmail(email);
        }
        checkUsable(user);
        return doLogin(user, request.getDeviceId());
    }

    @Override
    public LoginVO register(RegisterRequest request) {
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
     * 手机号首次短信登录时自动建号：账号取 {@code u + 手机号}，密码写入哨兵（未知），
     * 用户后续可在个人中心首次设置密码与昵称。
     */
    private User autoRegisterByPhone(String phone) {
        String username = "u" + phone;
        int attempt = 0;
        while (userService.existsUsername(username) && attempt < 5) {
            attempt++;
            username = "u" + phone + attempt;
        }
        String nickname = "用户" + phone.substring(phone.length() - 4);
        log.info("手机号 {} 未注册，自动创建账号 {}", TextUtil.maskPhone(phone), username);
        return userService.createAutoUser(username, nickname, phone, null);
    }

    /**
     * 邮箱首次验证码登录时自动建号：账号由邮箱本地部分派生（过滤非法字符），
     * 密码写入哨兵，昵称默认取账号，用户后续可自行设置。
     */
    private User autoRegisterByEmail(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        // 账号只保留字母数字下划线，其余字符一律剔除，避免违反账号规则
        String base = local.replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "u" + base;
        }
        if (base.length() > 24) {
            base = base.substring(0, 24);
        }
        String username = base;
        int attempt = 0;
        while (userService.existsUsername(username) && attempt < 5) {
            attempt++;
            username = base + attempt;
        }
        log.info("邮箱 {} 未注册，自动创建账号 {}", TextUtil.maskEmail(email), username);
        return userService.createAutoUser(username, username, null, email);
    }

    /**
     * 完成 Sa-Token 登录并组装返回体。
     *
     * <p>不再做同设备顶号：同一浏览器的多个标签页、同一桌面端的多个窗口共用一个设备位，
     * 顶号会把用户已经打开的其它页面挤下线（两端拿的是同一个登录 token，被顶的一方既没换账号也没断网）。
     * 配置 is-concurrent=true + is-share=false，每次登录签发独立 token，各端互不影响。
     */
    private LoginVO doLogin(User user, String deviceId) {
        String device = DeviceType.codeOf(deviceId);

        StpUtil.login(user.getId(), StpUtil.createSaLoginParameter().setDeviceType(device));
        userService.touchLogin(user.getId(), SecurityUtil.getClientIp());
        notifyOnlineState(user.getId(), true);
        return buildLoginVO(StpUtil.getTokenInfo(), device, userService.getProfile(user.getId()));
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
