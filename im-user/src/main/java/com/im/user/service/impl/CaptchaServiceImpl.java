package com.im.user.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.RedisKeys;
import com.im.common.exception.BusinessException;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import com.im.user.dto.req.SendEmailRequest;
import com.im.user.dto.req.SendSmsRequest;
import com.im.user.dto.vo.CaptchaImageVO;
import com.im.user.dto.vo.EmailSendVO;
import com.im.user.dto.vo.SmsSendVO;
import com.im.user.service.CaptchaService;
import com.im.user.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 验证码服务实现。
 *
 * <p>图形验证码使用 Java AWT 直接绘制，不引入任何三方验证码库；
 * 短信验证码的下发交给 {@link SmsSender}（默认 Mock：只写日志，不接真实服务商），
 * 邮件验证码走 QQ 邮箱 SMTP。
 *
 * <p>开发环境下可通过 {@code im.captcha.expose-sms-code} / {@code expose-email-code}
 * 在响应中回显验证码以便联调；{@code mock-mail-when-unconfigured} 则让 SMTP 未配置时
 * 邮箱链路也能跑通。三个开关在 prod profile 里都显式为 false。
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

    /**
     * 合法场景白名单。
     *
     * <p>场景会被拼进 Redis 键名（{@code im:captcha:sms:{scene}:{phone}}），
     * 而它是客户端传来的任意字符串：不白名单校验的话，传个带 {@code :} 的场景
     * 就能构造出跨越命名空间的键，或把自己发的码写到别人的键上。
     */
    private static final Set<String> SCENES = Set.of(
            SendSmsRequest.SCENE_LOGIN, SendSmsRequest.SCENE_REGISTER,
            SendSmsRequest.SCENE_BIND, SendSmsRequest.SCENE_RESET);

    private final RedisUtil redisUtil;
    private final ImProperties properties;
    /** 短信下发通道，默认 MockSmsSender；接真实服务商时换一个实现即可 */
    private final SmsSender smsSender;
    /** QQ 邮箱 SMTP 未配置（无 spring-boot-starter-mail 或缺少账号）时为空，发信时才报错 */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** 发件人地址，取 QQ 邮箱账号 */
    @Value("${spring.mail.username:}")
    private String mailFrom;

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
        // 场景先归一：非法场景要在消耗图形验证码之前就被拒，
        // 否则用户白白浪费一张图，重发时还得重新识别一次
        String scene = sceneOf(request.getScene());
        // 绑定手机号场景下用户已登录、是本人操作，跳过图形验证码闸门；
        // 登录/注册/找回密码等匿名场景仍先校验图形验证码，防止脚本化短信轰炸（一次性消费）
        if (!SendSmsRequest.SCENE_BIND.equals(scene)) {
            verifyImage(request.getCaptchaKey(), request.getCaptchaCode());
        }

        long interval = properties.getCaptcha().getSmsIntervalSeconds();
        String limitKey = RedisKeys.captchaSmsLimit(phone);
        // setIfAbsent 天然原子，用它同时完成「频率限制判断」与「占位」
        if (!redisUtil.setIfAbsent(limitKey, "1", Duration.ofSeconds(interval))) {
            throw new BusinessException(ResultCode.SMS_SEND_TOO_FREQUENT);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long ttl = properties.getCaptcha().getSmsTtlSeconds();
        redisUtil.set(RedisKeys.captchaSms(scene, phone), code, Duration.ofSeconds(ttl));
        try {
            smsSender.sendCode(phone, sceneName(scene), code, ttl);
        } catch (Exception e) {
            // 下发失败时回滚验证码与频率占位，否则用户会被「60 秒内只能发一次」
            // 锁在一个根本没发出去的码上，只能干等
            redisUtil.delete(RedisKeys.captchaSms(scene, phone));
            redisUtil.delete(limitKey);
            log.error("短信验证码下发失败: phone={}, channel={}, {}",
                    TextUtil.maskPhone(phone), smsSender.provider(), e.getMessage());
            throw new BusinessException(ResultCode.SMS_SEND_FAILED);
        }

        long retryAfter = redisUtil.getExpire(limitKey);
        return SmsSendVO.builder()
                .phone(TextUtil.maskPhone(phone))
                .expiresIn(ttl)
                .retryAfter(retryAfter > 0 ? retryAfter : interval)
                .debugCode(properties.getCaptcha().isExposeSmsCode() ? code : null)
                .build();
    }

    @Override
    public void verifySms(String scene, String phone, String smsCode) {
        if (TextUtil.isBlank(phone) || TextUtil.isBlank(smsCode)) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
        // 取出即删除：一次验证码只能使用一次，也堵死了 6 位码的暴力尝试
        String expected = redisUtil.getAndDelete(RedisKeys.captchaSms(sceneOf(scene), phone));
        if (expected == null || !expected.equals(smsCode.trim())) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
    }

    @Override
    public EmailSendVO sendEmail(SendEmailRequest request) {
        String email = request.getEmail() == null ? null : request.getEmail().trim();
        String scene = sceneOf(request.getScene());
        // 图形验证码闸门：先校验通过才发送邮件，防止脚本化邮箱轰炸（一次性消费）
        verifyImage(request.getCaptchaKey(), request.getCaptchaCode());

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        boolean mailConfigured = mailSender != null && TextUtil.isNotBlank(mailFrom);
        if (!mailConfigured && !properties.getCaptcha().isMockMailWhenUnconfigured()) {
            // 未引入 spring-boot-starter-mail 或未配置 MAIL_USERNAME，无法发信
            throw new BusinessException(ResultCode.MAIL_NOT_CONFIGURED);
        }

        long interval = properties.getCaptcha().getEmailIntervalSeconds();
        String limitKey = RedisKeys.captchaEmailLimit(email);
        // setIfAbsent 原子完成「频率限制判断」与「占位」
        if (!redisUtil.setIfAbsent(limitKey, "1", Duration.ofSeconds(interval))) {
            throw new BusinessException(ResultCode.EMAIL_SEND_TOO_FREQUENT);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long ttl = properties.getCaptcha().getEmailTtlSeconds();
        if (mailConfigured) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(email);
                message.setSubject("【IM】" + sceneName(scene) + "验证码");
                message.setText("您的" + sceneName(scene) + "验证码为：" + code + "，" + (ttl / 60) + " 分钟内有效。\n"
                        + resetTip(scene) + "如非本人操作，请忽略本邮件。");
                mailSender.send(message);
            } catch (Exception e) {
                // 发信失败时释放频率限制占位，允许用户立即重试
                redisUtil.delete(limitKey);
                log.error("验证码邮件发送失败: email={}, {}", TextUtil.maskEmail(email), e.getMessage());
                throw new BusinessException(ResultCode.MAIL_SEND_FAILED);
            }
        } else {
            // 开发降级：SMTP 未配时不真发信，验证码照常写入 Redis 并参与校验，
            // 邮箱登录与邮箱找回密码因此能在没有 QQ 授权码的机器上联调
            log.info("【Mock 邮件】SMTP 未配置，向 {} 发送{}验证码 {}（仅日志，未真实发信）",
                    TextUtil.maskEmail(email), sceneName(scene),
                    properties.getCaptcha().isExposeEmailCode() ? code : "***");
        }
        redisUtil.set(RedisKeys.captchaEmail(scene, email), code, Duration.ofSeconds(ttl));
        log.info("向 {} 发送{}邮箱验证码，{} 秒内有效", TextUtil.maskEmail(email), sceneName(scene), ttl);

        long retryAfter = redisUtil.getExpire(limitKey);
        return EmailSendVO.builder()
                .email(TextUtil.maskEmail(email))
                .expiresIn(ttl)
                .retryAfter(retryAfter > 0 ? retryAfter : interval)
                .debugCode(properties.getCaptcha().isExposeEmailCode() ? code : null)
                .build();
    }

    @Override
    public void verifyEmail(String scene, String email, String emailCode) {
        if (TextUtil.isBlank(email) || TextUtil.isBlank(emailCode)) {
            throw new BusinessException(ResultCode.EMAIL_CODE_ERROR);
        }
        String expected = redisUtil.getAndDelete(RedisKeys.captchaEmail(sceneOf(scene), email.trim()));
        if (expected == null || !expected.equals(emailCode.trim())) {
            throw new BusinessException(ResultCode.EMAIL_CODE_ERROR);
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
        if (SendSmsRequest.SCENE_REGISTER.equals(scene)) {
            return "注册";
        }
        if (SendSmsRequest.SCENE_BIND.equals(scene)) {
            return "绑定手机";
        }
        if (SendSmsRequest.SCENE_RESET.equals(scene)) {
            return "找回密码";
        }
        return "登录";
    }

    /**
     * 找回密码场景额外提醒一句后果：重置会踢掉所有已登录设备，
     * 也让收到邮件但并未操作的人意识到账号可能被盗。
     */
    private String resetTip(String scene) {
        return SendSmsRequest.SCENE_RESET.equals(scene)
                ? "重置成功后，该账号在所有设备上的登录态都会失效。\n"
                : "";
    }

    /**
     * 场景归一与白名单校验：空值归为登录场景（兼容旧客户端不传 scene），
     * 白名单之外的一律拒，理由见 {@link #SCENES}。
     */
    private String sceneOf(String scene) {
        String value = TextUtil.isBlank(scene)
                ? SendSmsRequest.SCENE_LOGIN
                : scene.trim().toLowerCase(Locale.ROOT);
        if (!SCENES.contains(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知的验证码场景");
        }
        return value;
    }
}
