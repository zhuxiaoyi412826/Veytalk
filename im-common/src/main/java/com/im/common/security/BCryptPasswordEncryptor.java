package com.im.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 密码加密器，通过 {@code im.security.password-encoder=bcrypt} 启用。
 *
 * <p>相比 Argon2 内存开销更低、生态兼容性更好，密文形如 {@code $2a$10$...}。
 */
@Slf4j
public class BCryptPasswordEncryptor implements PasswordEncryptor {

    /** BCrypt 强度（log rounds），10 约对应单次 100ms 量级开销 */
    private static final int STRENGTH = 10;

    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(STRENGTH);

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.length() == 0) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.length() == 0
                || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } catch (Exception e) {
            log.warn("BCrypt 密码校验异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String algorithm() {
        return "bcrypt";
    }
}
