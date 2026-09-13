package com.im.common.security;

/**
 * 密码加密器抽象。
 *
 * <p>实现类通过 {@code im.security.password-encoder} 配置切换：
 * {@code argon2}（默认，抗 GPU 爆破能力更强）或 {@code bcrypt}（兼容性最好、开销更低）。
 *
 * <p>注意：{@link #matches} 只依赖密文自身携带的参数前缀，
 * 因此即使运行期切换了算法，历史密文依然可以正常校验。
 */
public interface PasswordEncryptor {

    /**
     * 加密明文密码。
     *
     * @param rawPassword 明文，不可为空
     * @return 带算法前缀的密文
     */
    String encode(CharSequence rawPassword);

    /**
     * 校验明文与密文是否匹配。
     *
     * @param rawPassword     明文
     * @param encodedPassword 密文
     * @return 任一入参为空或密文格式非法时返回 {@code false}，不抛异常
     */
    boolean matches(CharSequence rawPassword, String encodedPassword);

    /**
     * 当前算法标识，用于日志与自检。
     */
    String algorithm();
}
