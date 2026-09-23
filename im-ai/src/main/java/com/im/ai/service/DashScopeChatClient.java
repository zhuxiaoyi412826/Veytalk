package com.im.ai.service;

import com.im.ai.config.AiProperties;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DashScope（OpenAI 兼容协议）流式对话客户端。
 *
 * <p>不引入 Spring AI / OpenAI SDK，直接用 JDK HttpClient 调
 * {@code POST {base-url}/v1/chat/completions}（stream=true）：
 * 用到的只有「一个 POST + 逐行读 SSE」，任何 SDK 在这个需求面前都是负担，
 * 还会把依赖版本和 Spring Boot 4 绑在一起赌兼容性。
 *
 * <p>响应体是标准 OpenAI SSE：每行 {@code data: {json}}，增量文本在
 * {@code choices[0].delta.content}，结束标记 {@code data: [DONE]}。
 * {@code BodyHandlers.ofLines()} 按行惰性拉取，不会把整个流缓冲进内存。
 *
 * <p>调用方中断：{@code onDelta} 抛出任何 RuntimeException 都会终止读取并原样上抛，
 * 上层（InterviewService）用这一点实现「浏览器断开后不再白耗模型 token」。
 */
@Slf4j
@Service
public class DashScopeChatClient {

    private final AiProperties properties;
    private final JsonUtil jsonUtil;
    private final HttpClient httpClient;

    public DashScopeChatClient(AiProperties properties, JsonUtil jsonUtil) {
        this.properties = properties;
        this.jsonUtil = jsonUtil;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 发起一次流式对话，每收到一个文本增量回调一次 onDelta。
     *
     * @param messages OpenAI 格式的消息列表（role/content）
     * @param onDelta  增量文本回调；回调抛异常则中止流
     */
    public void streamChat(List<Map<String, String>> messages, Consumer<String> onDelta) {
        AiProperties.Options options = properties.getChat().getOptions();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "AI 模型 API Key 未配置，请设置环境变量 ALI_BABA_API_KEY 后重启服务");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.getModel());
        body.put("messages", messages);
        body.put("temperature", options.getTemperature());
        body.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                // ofLines 场景下该超时约束「建立连接并收到响应」，流本身的读时长由模型侧决定
                .timeout(options.getTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(jsonUtil.toJson(body)))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            log.warn("AI 服务请求失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "AI 服务请求失败，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "AI 服务请求被中断");
        }

        if (response.statusCode() != 200) {
            String detail = response.body().limit(5).reduce("", (a, b) -> a + b);
            log.warn("AI 服务返回 {}: {}", response.statusCode(), detail);
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    "AI 服务返回异常（HTTP " + response.statusCode() + "），请检查 API Key 与模型名配置");
        }

        try (java.util.stream.Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (!line.startsWith("data:")) {
                    return;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty()) {
                    return;
                }
                if ("[DONE]".equals(payload)) {
                    // forEach 里不能 break，用异常跳出；下面 catch 住当作正常结束
                    throw new StreamDoneException();
                }
                JsonNode node = jsonUtil.mapper().readTree(payload);
                JsonNode error = node.get("error");
                if (error != null && !error.isNull()) {
                    String message = error.path("message").asText("AI 服务返回错误");
                    throw new BusinessException(ResultCode.SYSTEM_ERROR, message);
                }
                JsonNode delta = node.at("/choices/0/delta/content");
                if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
                    onDelta.accept(delta.asText());
                }
            });
        } catch (StreamDoneException done) {
            // 正常结束
        }
    }

    /** 仅用于从 forEach 里跳出 SSE 读取循环 */
    private static class StreamDoneException extends RuntimeException {
    }
}
