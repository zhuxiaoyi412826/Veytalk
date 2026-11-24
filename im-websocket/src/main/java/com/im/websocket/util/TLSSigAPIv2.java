package com.im.websocket.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

/**
 * 腾讯云 TLSSigAPIv2（UserSig）签名工具，<b>仅微信小程序音视频通话链路使用</b>。
 *
 * <p>对齐腾讯云官方 TLS 2.0 算法，只用 JDK 标准库（{@link javax.crypto.Mac} +
 * {@link java.util.zip.Deflater} + {@link java.util.Base64}），不引入任何第三方 SDK——
 * 与本项目「内网 / 离线可部署、不绑厂商依赖」的取向一致。
 *
 * <p>四步顺序不可变（详见 md/开发/音视频/TRTC小程序音视频通话实现规范.md 第三节）：
 * <ol>
 *   <li>拼签名原文（字段名与换行逐字一致）；</li>
 *   <li>以 {@code secretKey} 为密钥做 HMAC-SHA256，标准 Base64 得到 {@code TLS.sig}；</li>
 *   <li>组装 JSON（TLS.ver / identifier / sdkappid / expire / time / sig）；</li>
 *   <li>JSON 做 zlib deflate 压缩后 Base64，再做三处字符替换 {@code + → *}{@code / → -}{@code = → _}。</li>
 * </ol>
 *
 * <p>线程安全：无可变状态，构造入参 {@code (sdkAppId, secretKey)} 全程只读，可按需 new。
 */
public class TLSSigAPIv2 {

    private final long sdkAppId;
    private final String key;

    public TLSSigAPIv2(long sdkAppId, String key) {
        this.sdkAppId = sdkAppId;
        this.key = key;
    }

    /**
     * 为指定用户现签一个 UserSig。
     *
     * @param userId        用户 ID，用雪花 ID 的<b>字符串</b>形式，全链路不做数值转换
     * @param expireSeconds 签名有效期（秒），默认由配置给 7 天
     * @return 可直接拼进 {@code room://...&userSig=} 的签名串
     */
    public String genUserSig(String userId, long expireSeconds) {
        long currTime = System.currentTimeMillis() / 1000;

        // ① 签名原文：字段名与换行必须与官方逐字一致，多一个空格都会导致验签失败
        String sigSource = "TLS.identifier:" + userId + "\n"
                + "TLS.sdkappid:" + sdkAppId + "\n"
                + "TLS.time:" + currTime + "\n"
                + "TLS.expire:" + expireSeconds + "\n";

        // ② HMAC-SHA256 + 标准 Base64
        String sig;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(sigSource.getBytes(StandardCharsets.UTF_8));
            sig = Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("生成 TRTC UserSig 失败", e);
        }

        // ③ 组装 JSON。userId 为数字型雪花 ID 字符串、sig 为标准 Base64，均无需转义
        String sigDoc = "{"
                + "\"TLS.ver\":\"2.0\","
                + "\"TLS.identifier\":\"" + userId + "\","
                + "\"TLS.sdkappid\":" + sdkAppId + ","
                + "\"TLS.expire\":" + expireSeconds + ","
                + "\"TLS.time\":" + currTime + ","
                + "\"TLS.sig\":\"" + sig + "\""
                + "}";

        // ④ zlib deflate → Base64 → 三处字符替换
        byte[] compressed = deflate(sigDoc.getBytes(StandardCharsets.UTF_8));
        String base64 = Base64.getEncoder().encodeToString(compressed);
        return base64.replace('+', '*').replace('/', '-').replace('=', '_');
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }
}
