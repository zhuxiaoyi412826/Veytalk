package com.im.user.sms;

import com.im.common.config.ImProperties;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 短信通道：不发真实短信，只写日志。
 *
 * <p>未配置 {@code im.sms.provider} 时默认装配本实现，因此本地开发不需要任何短信账号
 * 就能把「发码 → 校验」整条链路跑通。验证码本身照常写入 Redis 并参与校验，
 * 拿码的方式有两种：开发环境响应体里的 {@code debugCode} 回显，或本类的日志。
 *
 * <p>日志里是否带明文验证码，跟着 {@code im.captcha.expose-sms-code} 走：
 * 该开关在生产 profile 里显式为 false，所以即便有人误把 Mock 通道部署上线，
 * 也不会在日志文件里留下一串可直接登录/改密码的验证码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.sms.provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    private final ImProperties properties;

    @Override
    public void sendCode(String phone, String sceneName, String code, long ttlSeconds) {
        if (properties.getCaptcha().isExposeSmsCode()) {
            log.info("【Mock 短信】向 {} 发送{}验证码 {}，{} 秒内有效",
                    TextUtil.maskPhone(phone), sceneName, code, ttlSeconds);
        } else {
            log.info("【Mock 短信】向 {} 发送{}验证码（内容不回显），{} 秒内有效",
                    TextUtil.maskPhone(phone), sceneName, ttlSeconds);
        }
    }

    @Override
    public String provider() {
        return "mock";
    }
}
