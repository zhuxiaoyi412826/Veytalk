package com.im.remote.agent;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * AES-256-GCM 载荷加解密，与前端 WebCrypto、服务端下发密钥三方兼容：
 *
 * <p>密文格式 = {@code 12B IV + 密文 + 16B GCM Tag}——Java {@code Cipher} 的
 * GCM 输出天然是「密文||Tag」拼接，WebCrypto 解密时也要求 Tag 附在密文尾部，
 * 因此两侧无需任何额外拆分。密钥来自会话建立时服务端下发的 session-start 帧。
 *
 * <p>只加密二进制帧 payload；文本帧信封保持明文（中继要靠 type/sid 路由）。
 */
public final class AesCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    private final byte[] key;

    public AesCipher(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-256 密钥必须 32 字节");
        }
        this.key = key.clone();
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plain);
            byte[] out = new byte[IV_LENGTH + body.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(body, 0, out, IV_LENGTH, body.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    public byte[] decrypt(byte[] ivAndCipher) {
        if (ivAndCipher == null || ivAndCipher.length <= IV_LENGTH) {
            throw new IllegalArgumentException("密文长度不足");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, ivAndCipher, 0, IV_LENGTH));
            return cipher.doFinal(ivAndCipher, IV_LENGTH, ivAndCipher.length - IV_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败: " + e.getMessage(), e);
        }
    }
}
