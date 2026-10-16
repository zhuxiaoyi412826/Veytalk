package com.im.message.intelligence;

import com.im.common.spi.TranslationSpi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消息翻译的内置演示桩：仅在 {@code im.intelligence.mock-when-unconfigured=true} 时注册。
 *
 * <p>与 {@link MockSpeechToTextSpi} 同构，用于在无真实翻译厂商时跑通「前端 → 接口 → SPI → 结果」链路。
 * 返回文本带「模拟」前缀且不冒充真实译文；生产关掉本开关后即降级。接真实厂商时提供一个
 * {@code @Primary} 的 {@link TranslationSpi} 实现即可覆盖。
 */
@Component
@ConditionalOnProperty(prefix = "im.intelligence", name = "mock-when-unconfigured", havingValue = "true")
public class MockTranslationSpi implements TranslationSpi {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String translate(String text, String targetLang) {
        return "[模拟翻译 → " + (targetLang == null ? "en-US" : targetLang) + "] "
                + (text == null ? "" : text)
                + "（演示桩直接回显原文，接入真实翻译服务后返回实际译文）";
    }
}
