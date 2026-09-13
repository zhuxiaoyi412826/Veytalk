package com.im.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id 密码加密器，项目默认实现。
 *
 * <p>依赖 BouncyCastle（{@code bcprov-jdk18on}）提供 Argon2 算法，
 * 参数取 Spring Security v5.8 推荐默认值：saltLength=16、hashLength=32、
 * parallelism=1、memory=16384KB、iterations=2。
 */
@Slf4j
public class Argon2PasswordEncryptor implements PasswordEncryptor {

    private final Argon2PasswordEncoder delegate = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

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
            // 密文格式非法（例如库里存的是明文或历史算法），按校验失败处理而不是抛到全局兜底
            log.warn("Argon2 密码校验异常: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String algorithm() {
        return "argon2";
    }
}
