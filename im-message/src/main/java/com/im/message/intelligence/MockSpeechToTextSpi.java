package com.im.message.intelligence;

import com.im.common.spi.SpeechToTextSpi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 语音转文字的内置演示桩：仅在 {@code im.intelligence.mock-when-unconfigured=true} 时注册。
 *
 * <p>定位与 {@code captcha.mockMailWhenUnconfigured} 完全一致——没有真实 ASR 厂商时，让
 * 「前端入口 → 接口 → SPI → 结果展示」这条链路可以被完整跑通与演示。返回的文本带着明显的
 * 「模拟」前缀，绝不冒充真实识别结果；生产环境关掉本开关（默认即关），届时没有任何
 * {@link SpeechToTextSpi} bean，消费方直接降级回「服务未配置」。
 *
 * <p>接入真实厂商：提供一个自己的 {@link SpeechToTextSpi} 实现（必要时标 {@code @Primary}），
 * 本桩会因优先级让位或被直接关闭。
 */
@Component
@ConditionalOnProperty(prefix = "im.intelligence", name = "mock-when-unconfigured", havingValue = "true")
public class MockSpeechToTextSpi implements SpeechToTextSpi {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String toText(String audioUrl, Integer durationSeconds) {
        int seconds = durationSeconds == null ? 0 : durationSeconds;
        return "[模拟转写 · " + seconds + " 秒] 这里是由演示桩生成的占位文本，"
                + "接入真实语音识别服务后将返回实际识别内容，当前仅供联调与展示链路使用。";
    }
}
