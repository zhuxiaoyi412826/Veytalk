package com.im.common.security;

import com.im.common.config.ImProperties;
import com.im.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全相关 Bean 装配：密码加密器与短时 JWT 工具。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class SecurityBeanConfig {

    /**
     * Argon2 实现，{@code im.security.password-encoder=argon2} 或未配置时生效。
     */
    @Bean
    @ConditionalOnMissingBean(PasswordEncryptor.class)
    @ConditionalOnProperty(prefix = "im.security", name = "password-encoder",
            havingValue = "argon2", matchIfMissing = true)
    public PasswordEncryptor argon2PasswordEncryptor() {
        log.info("密码加密器启用 Argon2id");
        return new Argon2PasswordEncryptor();
    }

    /**
     * BCrypt 实现，{@code im.security.password-encoder=bcrypt} 时生效。
     */
    @Bean
    @ConditionalOnProperty(prefix = "im.security", name = "password-encoder", havingValue = "bcrypt")
    public PasswordEncryptor bcryptPasswordEncryptor() {
        log.info("密码加密器启用 BCrypt");
        return new BCryptPasswordEncryptor();
    }

    /**
     * 短时票据签发工具（WebSocket 握手票据、文件访问票据）。
     *
     * <p>登录态 token 由 Sa-Token + sa-token-jwt 负责，两者密钥互相独立。
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtUtil jwtUtil(ImProperties properties) {
        return new JwtUtil(properties.getJwt().getSecret());
    }
}
