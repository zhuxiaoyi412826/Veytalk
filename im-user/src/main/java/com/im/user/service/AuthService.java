package com.im.user.service;

import com.im.user.dto.req.EmailLoginRequest;
import com.im.user.dto.req.LoginRequest;
import com.im.user.dto.req.RegisterRequest;
import com.im.user.dto.req.ResetPasswordRequest;
import com.im.user.dto.req.SendEmailRequest;
import com.im.user.dto.req.SendSmsRequest;
import com.im.user.dto.req.SmsLoginRequest;
import com.im.user.dto.vo.EmailSendVO;
import com.im.user.dto.vo.LoginVO;
import com.im.user.dto.vo.SmsSendVO;
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
     * 邮箱 + 邮箱验证码登录，邮箱未注册时自动注册。
     */
    LoginVO loginByEmail(EmailLoginRequest request);

    /**
     * 注册并直接返回登录态，省去前端的二次登录。
     */
    LoginVO register(RegisterRequest request);

    /**
     * 发送找回密码用的短信验证码。
     *
     * <p>与登录发码的两个区别：场景强制为 {@code reset}（客户端传什么都无效），
     * 且手机号必须已绑定账号——找回密码不能像验证码登录那样顺手建号，
     * 否则任意手机号都能被用来探测系统里有没有这个人。
     */
    SmsSendVO sendResetSmsCode(SendSmsRequest request);

    /**
     * 发送找回密码用的邮箱验证码，约束同 {@link #sendResetSmsCode}。
     */
    EmailSendVO sendResetEmailCode(SendEmailRequest request);

    /**
     * 凭验证码重置密码，成功后注销该用户全部设备的登录态。
     */
    void resetPassword(ResetPasswordRequest request);

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
