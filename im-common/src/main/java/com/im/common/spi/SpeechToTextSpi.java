package com.im.common.spi;

/**
 * 语音转文字（ASR）契约。
 *
 * <p>这是一条依赖外部服务的链路：真实的语音识别需要接入云厂商 ASR（或自建模型服务），
 * 本期内网 / 离线部署不提供任何真实实现，消费方（im-message）通过 {@code ObjectProvider}
 * 拿到 bean 集合，无实现或 {@link #available()} 为假时优雅降级，回一句「服务未配置」的提示。
 *
 * <p>扩展方式：新增一个实现本接口的 {@code @Primary} bean（或把内置桩关掉），接入厂商 SDK 后
 * 在 {@link #toText} 里返回识别文本即可，调用链路无需改动。
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
}
