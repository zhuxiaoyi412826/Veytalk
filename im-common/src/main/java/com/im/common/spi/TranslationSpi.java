package com.im.common.spi;

/**
 * 消息翻译契约。
 *
 * <p>与 {@link SpeechToTextSpi} 同属「依赖外部服务」的能力：真实翻译需要接入云厂商翻译 API
 * （或自建模型服务），本期内网 / 离线部署不提供任何真实实现，消费方通过 {@code ObjectProvider}
 * 拿到 bean，无实现或 {@link #available()} 为假时优雅降级，回一句「服务未配置」的提示。
 *
 * <p>扩展方式：新增一个实现本接口的 {@code @Primary} bean，接入厂商后在 {@link #translate} 里返回
 * 译文即可，调用链路无需改动。
 */
public interface TranslationSpi {

    /**
     * 当前是否具备可用的翻译能力（厂商凭据已配置、服务可达）。
     */
    boolean available();

    /**
     * 把文本翻译为目标语言。
     *
     * @param text       待翻译原文（已通过敏感词与 XSS 处理的正文）
     * @param targetLang 目标语言代码（如 {@code en-US}、{@code ja-JP}）
     * @return 译文；翻译失败或无内容时可返回空串，由调用方决定是否降级
     */
    String translate(String text, String targetLang);
}
