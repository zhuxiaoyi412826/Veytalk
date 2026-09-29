package com.im.user.service;

import com.im.user.dto.req.SendEmailRequest;
import com.im.user.dto.req.SendSmsRequest;
import com.im.user.dto.vo.CaptchaImageVO;
import com.im.user.dto.vo.EmailSendVO;
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
     * 发送短信验证码，带 60 秒频率限制；具体下发通道由 {@code SmsSender} 实现决定
     * （默认 Mock：只写日志，不接真实服务商）。
     *
     * <p>当 {@code im.captcha.image-required=true} 时，发送前先校验图形验证码，
     * 作为防短信轰炸的闸门；图形验证码一次性消费。绑定手机号场景例外（已登录的本人操作）。
     */
    SmsSendVO sendSms(SendSmsRequest request);

    /**
     * 校验指定场景的短信验证码，成功后立即作废。
     *
     * <p>场景必传且参与 Redis 键名：为登录发的码不能用来重置密码，反之亦然。
     * 验证码本身一次性消费（取出即删），因此 6 位码无法被暴力猜测。
     *
     * @param scene {@code SendSmsRequest.SCENE_*} 之一
     */
    void verifySms(String scene, String phone, String smsCode);

    /**
     * 发送邮箱验证码（通过 QQ 邮箱 SMTP），带频率限制。
     *
     * <p>SMTP 未配置且 {@code im.captcha.mock-mail-when-unconfigured=true} 时降级为只写日志，
     * 供开发环境联调邮箱登录与邮箱找回密码；生产环境该开关必须为 false。
     */
    EmailSendVO sendEmail(SendEmailRequest request);

    /**
     * 校验指定场景的邮箱验证码，成功后立即作废。场景隔离理由同 {@link #verifySms}。
     *
     * @param scene {@code SendEmailRequest.SCENE_*} 之一
     */
    void verifyEmail(String scene, String email, String emailCode);
}
