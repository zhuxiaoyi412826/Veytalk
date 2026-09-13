package com.im.user.service;

import com.im.user.dto.req.LoginRequest;
import com.im.user.dto.req.RegisterRequest;
import com.im.user.dto.req.SmsLoginRequest;
import com.im.user.dto.vo.LoginVO;
import com.im.user.dto.vo.UserVO;

/**
 * 认证服务：注册、登录、注销、续期。
 */
public interface AuthService {

    /**
     * 账号 / 手机号 + 密码登录。
     */
    LoginVO login(LoginRequest request);

    /**
     * 手机号 + 短信验证码登录，手机号未注册时自动注册。
     */
    LoginVO loginBySms(SmsLoginRequest request);

    /**
     * 注册并直接返回登录态，省去前端的二次登录。
     */
    LoginVO register(RegisterRequest request);

    /**
     * 注销当前设备。
     */
    void logout();

    /**
     * 注销指定用户的全部设备，修改密码等敏感操作后使用。
     */
    void logoutEverywhere(Long userId);

    /**
     * 续签：换发一个新 token 并注销旧 token。
     *
     * <p>JWT 混合模式下过期时间写死在 token 里，无法像 Redis 模式那样单纯延长 TTL，
     * 因此这里采用「先签发新的、再注销旧的」策略，中间不会出现无凭证窗口。
     */
    LoginVO refresh();

    /**
     * 当前登录用户资料。
     */
    UserVO currentUser();
}
