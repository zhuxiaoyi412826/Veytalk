package com.im.common.util;

import cn.hutool.core.convert.Convert;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 轻量 JWT 工具，基于 hutool-jwt（HS256）。
 *
 * <p>登录态 token 由 Sa-Token + sa-token-jwt 负责，本工具只用于签发短时票据，
 * 例如 WebSocket 握手票据、文件访问票据，避免在 URL 中长期暴露登录凭证。
 */
@Slf4j
public class JwtUtil {

    /** 载荷键：用户 ID */
    public static final String CLAIM_USER_ID = "userId";
    /** 载荷键：设备标识 */
    public static final String CLAIM_DEVICE_ID = "deviceId";
    /** 载荷键：票据用途 */
    public static final String CLAIM_PURPOSE = "purpose";

    private final byte[] secretKey;

    public JwtUtil(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("im.jwt.secret 未配置，无法初始化 JwtUtil");
        }
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 签发 token。
     *
     * @param claims 自定义载荷
     * @param ttl    有效期
     */
    public String createToken(Map<String, Object> claims, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();
        JWT jwt = JWT.create()
                .setIssuedAt(Date.from(now.atZone(ZoneId.systemDefault()).toInstant()))
                .setExpiresAt(Date.from(now.plus(ttl).atZone(ZoneId.systemDefault()).toInstant()))
                .setKey(secretKey);
        if (claims != null) {
            claims.forEach(jwt::setPayload);
        }
        return jwt.sign();
    }

    /**
     * 签发用户票据。
     */
    public String createToken(Long userId, String deviceId, String purpose, Duration ttl) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_DEVICE_ID, deviceId);
        claims.put(CLAIM_PURPOSE, purpose);
        return createToken(claims, ttl);
    }

    /**
     * 校验签名与有效期并返回载荷。
     *
     * @throws BusinessException 票据无效或已过期
     */
    public Map<String, Object> parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.WS_TICKET_INVALID);
        }
        try {
            if (!JWTUtil.verify(token, secretKey)) {
                throw new BusinessException(ResultCode.WS_TICKET_INVALID);
            }
            JWT jwt = JWTUtil.parseToken(token);
            JWTValidator.of(jwt).validateDate();
            Map<String, Object> payloads = new HashMap<>(jwt.getPayloads());
            return payloads;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.WS_TICKET_INVALID);
        }
    }

    /**
     * 解析票据中的用户 ID。
     */
    public Long parseUserId(String token) {
        return Convert.toLong(parse(token).get(CLAIM_USER_ID));
    }

    /**
     * 静默校验，失败返回 {@code null} 而不抛异常。
     */
    public Map<String, Object> parseQuietly(String token) {
        try {
            return parse(token);
        } catch (Exception e) {
            return null;
        }
    }
}
