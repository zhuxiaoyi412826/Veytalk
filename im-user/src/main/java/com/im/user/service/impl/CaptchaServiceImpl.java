package com.im.user.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.RedisKeys;
import com.im.common.exception.BusinessException;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import com.im.user.dto.req.SendSmsRequest;
import com.im.user.dto.vo.CaptchaImageVO;
import com.im.user.dto.vo.SmsSendVO;
import com.im.user.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 验证码服务实现。
 *
 * <p>图形验证码使用 Java AWT 直接绘制，不引入任何三方验证码库；
 * 短信验证码为 Mock 实现——写入 Redis 与日志，不接真实短信服务商，
 * 开发环境下可通过 {@code im.captcha.expose-sms-code} 在响应中回显以便联调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    private static final int IMAGE_WIDTH = 120;
    private static final int IMAGE_HEIGHT = 40;
    private static final int CODE_LENGTH = 4;
    private static final int LINE_COUNT = 8;
    private static final int NOISE_COUNT = 120;

    /** 字符池刻意剔除了 0/O、1/l/I 等易混淆字符 */
    private static final char[] CODE_POOL = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisUtil redisUtil;
    private final ImProperties properties;

    @Override
    public CaptchaImageVO generateImage() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_POOL[RANDOM.nextInt(CODE_POOL.length)]);
        }
        String code = builder.toString();
        String captchaKey = UUID.randomUUID().toString().replace("-", "");
        long ttl = properties.getCaptcha().getImageTtlSeconds();
        redisUtil.set(RedisKeys.captchaImage(captchaKey), code, Duration.ofSeconds(ttl));

        return CaptchaImageVO.builder()
                .captchaKey(captchaKey)
                .image(DATA_URI_PREFIX + draw(code))
                .expiresIn(ttl)
                .debugCode(properties.getCaptcha().isExposeImageCode() ? code : null)
                .build();
    }

    @Override
    public void verifyImage(String captchaKey, String captchaCode) {
        if (!properties.getCaptcha().isImageRequired()) {
            return;
        }
        if (TextUtil.isBlank(captchaKey) || TextUtil.isBlank(captchaCode)) {
            throw new BusinessException(ResultCode.CAPTCHA_ERROR);
        }
        // 取出即删除：一次验证码只能使用一次，避免被重放
        String expected = redisUtil.getAndDelete(RedisKeys.captchaImage(captchaKey));
        if (expected == null || !expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(ResultCode.CAPTCHA_ERROR);
        }
    }

    @Override
    public SmsSendVO sendSms(SendSmsRequest request) {
        String phone = request.getPhone();
        long interval = properties.getCaptcha().getSmsIntervalSeconds();
        String limitKey = RedisKeys.captchaSmsLimit(phone);
        // setIfAbsent 天然原子，用它同时完成「频率限制判断」与「占位」
        if (!redisUtil.setIfAbsent(limitKey, "1", Duration.ofSeconds(interval))) {
            throw new BusinessException(ResultCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long ttl = properties.getCaptcha().getSmsTtlSeconds();
        redisUtil.set(RedisKeys.captchaSms(phone), code, Duration.ofSeconds(ttl));
        log.info("【Mock 短信】向 {} 发送{}验证码 {}，{} 秒内有效",
                TextUtil.maskPhone(phone), sceneName(request.getScene()), code, ttl);

        long retryAfter = redisUtil.getExpire(limitKey);
        return SmsSendVO.builder()
                .phone(TextUtil.maskPhone(phone))
                .expiresIn(ttl)
                .retryAfter(retryAfter > 0 ? retryAfter : interval)
                .debugCode(properties.getCaptcha().isExposeSmsCode() ? code : null)
                .build();
    }

    @Override
    public void verifySms(String phone, String smsCode) {
        if (TextUtil.isBlank(phone) || TextUtil.isBlank(smsCode)) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
        String expected = redisUtil.getAndDelete(RedisKeys.captchaSms(phone));
        if (expected == null || !expected.equals(smsCode.trim())) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
    }

    /**
     * 绘制验证码图片并返回 Base64 编码的 PNG。
     */
    private String draw(String code) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            graphics.setColor(new Color(245, 247, 250));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            for (int i = 0; i < LINE_COUNT; i++) {
                graphics.setColor(randomColor(170, 220));
                graphics.drawLine(RANDOM.nextInt(IMAGE_WIDTH), RANDOM.nextInt(IMAGE_HEIGHT),
                        RANDOM.nextInt(IMAGE_WIDTH), RANDOM.nextInt(IMAGE_HEIGHT));
            }
            for (int i = 0; i < NOISE_COUNT; i++) {
                graphics.setColor(randomColor(180, 230));
                graphics.fillRect(RANDOM.nextInt(IMAGE_WIDTH), RANDOM.nextInt(IMAGE_HEIGHT), 1, 1);
            }

            // 使用逻辑字体，避免容器内缺少物理字体导致渲染异常
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            FontMetrics metrics = graphics.getFontMetrics();
            int step = (IMAGE_WIDTH - 16) / code.length();
            int baseline = (IMAGE_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
            for (int i = 0; i < code.length(); i++) {
                int x = 8 + i * step;
                double angle = (RANDOM.nextDouble() - 0.5) * 0.5;
                graphics.setColor(randomColor(20, 110));
                graphics.rotate(angle, x, baseline);
                graphics.drawString(String.valueOf(code.charAt(i)), x, baseline);
                graphics.rotate(-angle, x, baseline);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            log.error("图形验证码生成失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        } finally {
            graphics.dispose();
        }
    }

    private Color randomColor(int min, int max) {
        int range = Math.max(1, max - min);
        return new Color(min + RANDOM.nextInt(range), min + RANDOM.nextInt(range), min + RANDOM.nextInt(range));
    }

    private String sceneName(String scene) {
        if (SendSmsRequest.SCENE_REGISTER.equalsIgnoreCase(scene)) {
            return "注册";
        }
        if (SendSmsRequest.SCENE_BIND.equalsIgnoreCase(scene)) {
            return "绑定手机";
        }
        return "登录";
    }
}
