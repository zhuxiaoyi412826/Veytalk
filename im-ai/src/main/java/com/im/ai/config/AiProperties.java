package com.im.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 模型接入配置，绑定 {@code spring.ai.openai} 前缀。
 *
 * <p>刻意沿用 Spring AI 的配置坐标而不是自造前缀：项目没有引入 Spring AI
 * （其 starter 与 Spring Boot 4 的兼容矩阵尚未稳定），但 DashScope 的
 * compatible-mode 本身就是 OpenAI 协议，用 JDK HttpClient 直连即可。
 * 配置键保持 {@code spring.ai.openai.*} 的写法，将来若切换到真正的
 * Spring AI 依赖，yml 一行都不用改。
 *
 * <p>Boot 对没有自动配置认领的 {@code spring.ai.*} 键不会报错，
 * 这里的 {@code @ConfigurationProperties} 就是它们唯一的消费者。
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.openai")
public class AiProperties {

    /** OpenAI 兼容端点根地址（不含 /v1），默认阿里云百炼 compatible-mode */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";

    /** API Key，只从环境变量 ALI_BABA_API_KEY 注入；为空时面试接口返回明确的未配置错误 */
    private String apiKey = "";

    private Chat chat = new Chat();

    /** 语音链路（面试「按住说话」ASR 与「电话面试」TTS），同 baseUrl 同 apiKey，只是换模型 */
    private Audio audio = new Audio();

    @Data
    public static class Chat {
        private Options options = new Options();
    }

    @Data
    public static class Options {
        /** 百炼模型名：qwen-flash / qwen-plus / qwen-max */
        private String model = "qwen-flash";
        /** 采样温度。面试官要有稳定的追问风格，不宜过高 */
        private Double temperature = 0.7;
        /** 单次流式响应的读超时（从发起请求到收到响应头/首个分片） */
        private Duration timeout = Duration.ofSeconds(60);
    }

    /**
     * 语音模型参数。
     *
     * <p>两个能力都是百炼 compatible-mode 上的现成模型，复用同一个 API Key：
     * ASR 走 qwen3-asr-flash（chat/completions 携带 input_audio，可非流式）；
     * TTS 走 omni 系列（modalities 要 text+audio，强制流式，后端聚合分片后整段返回）。
     * 模型名可换而不改代码，与 chat 那个同理。
     */
    @Data
    public static class Audio {
        /** 语音转文字模型（输入 16k 单声道 WAV 的 base64，输出纯文本） */
        private String asrModel = "qwen3-asr-flash";
        /** 语音合成模型（omni 系列，输出 base64 wav 分片） */
        private String ttsModel = "qwen3-omni-flash";
        /** 神经音色的说话人名（随 ttsModel 的型号而变，换模型时到这个模型的音色列表里选） */
        private String ttsVoice = "Tina";
        /** ASR 单次转写超时（非流式，一问一答） */
        private Duration asrTimeout = Duration.ofSeconds(30);
        /** TTS 聚合超时（流式收完全部音频分片的总时长上限） */
        private Duration ttsTimeout = Duration.ofSeconds(60);
    }
}
