package com.im.group.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * 二维码工具：把一段文本编成 PNG 并输出 base64 数据 URL。
 *
 * <p>本工具目前只服务群邀请链接二维码，好友/远程接入的二维码如果需要也可以直接调这里的 {@link #toPngBase64}。
 * 后端生成而不是前端，是因为前端要引入 QR 库（~几百 KB 代码或新的 npm 依赖）；本模块已引入 zxing 且
 * {@code MatrixToImageWriter} 走的是 java.awt，服务端完全能承担。返回 data URL 让 &lt;img&gt; 直接嵌，不用另开下载端点。
 */
public final class QrCodeUtil {

    /** 默认边长（像素），邀请链接一般 60 字符左右，300px 已足够手机扫清楚 */
    public static final int DEFAULT_SIZE = 300;

    private QrCodeUtil() {
    }

    /**
     * 生成 PNG 二维码的 base64 数据 URL：{@code data:image/png;base64,...}。
     *
     * @param content 待编码的文本（通常是完整 URL）
     * @param size    方形边长像素
     */
    public static String toPngBase64(String content, int size) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("二维码内容不能为空");
        }
        int safeSize = size <= 0 ? DEFAULT_SIZE : size;
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, safeSize, safeSize, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // 前向兼容：MatrixToImageConfig 默认黑白色调，这里保持默认
            MatrixToImageWriter.writeToStream(matrix, "PNG", out, new MatrixToImageConfig());
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("二维码生成失败: " + e.getMessage(), e);
        }
    }

    /** 使用默认边长 300px 的便捷重载 */
    public static String toPngBase64(String content) {
        return toPngBase64(content, DEFAULT_SIZE);
    }
}
