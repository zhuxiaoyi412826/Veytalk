package com.im.common.spi;

/**
 * 语音转文字（ASR）契约。
 *
 * <p>这是一条依赖外部服务的链路：真实的语音识别要调云厂商 ASR（或自建模型服务）。
 * 消费方（im-message）通过 {@code ObjectProvider} 拿到 bean，无实现或 {@link #available()}
 * 为假时优雅降级，回一句「服务未配置」的提示，不冒充真实结果。
 *
 * <p>真实实现见 im-ai 的 {@code DashScopeSpeechToTextSpi}（复用面试「按住说话」那一条
 * 百炼 ASR 链路，{@code @Primary} 覆盖内置桩）；{@code im.intelligence.mock-when-unconfigured}
 * 打开时的 {@code MockSpeechToTextSpi} 只用于离线联调。再接厂商时同样只需提供一个
 * 实现本接口的 {@code @Primary} bean，调用链路无需改动。
 */
public interface SpeechToTextSpi {

    /**
     * 当前是否具备可用的识别能力（厂商凭据已配置、服务可达）。
     *
     * <p>为假时调用方不应尝试 {@link #toText}，直接降级。
     */
    boolean available();

    /**
     * 把一条语音识别为文本。
     *
     * @param audioUrl        语音文件的服务端可访问地址（受控下载地址或预签名直链）
     * @param durationSeconds 语音时长（秒），部分厂商按秒计费或限长，可空
     * @return 识别出的文本；识别失败或无内容时可返回空串，由调用方决定是否降级
     */
    String toText(String audioUrl, Integer durationSeconds);

    /**
     * 带文件 ID 的识别入口，实现方优先用这一条。
     *
     * <p>{@link #toText(String, Integer)} 只给了地址，而地址要么是受控地址（要登录态，
     * 服务端自己 http 回环去取既绕又过不了鉴权）、要么是预签名直链（有时效，SPI 实现
     * 拿到时可能已经过期）。给 fileId 就能直接走 {@code FileStorageSpi} 读字节，
     * MinIO 与本地磁盘两种存储都不需要经公网。
     *
     * <p>默认委托旧签名，只为兼容既有实现（内置桩只需要地址即可造占位文本）；
     * 真实厂商实现应覆盖本方法。
     *
     * @param fileId 语音文件 ID，可空（历史消息或文件已销毁时为空）
     */
    default String toText(Long fileId, String audioUrl, Integer durationSeconds) {
        return toText(audioUrl, durationSeconds);
    }
}
