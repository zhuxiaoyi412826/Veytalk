package com.im.user.service;

import com.im.user.dto.req.SendSmsRequest;
import com.im.user.dto.vo.CaptchaImageVO;
import com.im.user.dto.vo.SmsSendVO;

/**
 * 验证码服务：图形验证码与短信验证码。
 */
public interface CaptchaService {

    /**
     * 生成图形验证码，答案写入 Redis。
     */
    CaptchaImageVO generateImage();

    /**
     * 校验图形验证码，无论成功与否都会作废该验证码，防止暴力重试。
     *
     * <p>当 {@code im.captcha.image-required=false} 时直接放行，便于脚本化联调。
     */
    void verifyImage(String captchaKey, String captchaCode);

    /**
     * 发送短信验证码（Mock 实现：写 Redis + 日志），带 60 秒频率限制。
     */
    SmsSendVO sendSms(SendSmsRequest request);

    /**
     * 校验短信验证码，成功后立即作废。
     */
    void verifySms(String phone, String smsCode);
}
