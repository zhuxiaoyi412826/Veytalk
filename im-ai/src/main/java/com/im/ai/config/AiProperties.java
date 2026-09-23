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
}
