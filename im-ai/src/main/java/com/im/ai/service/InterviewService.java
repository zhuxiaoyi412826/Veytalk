package com.im.ai.service;

import com.im.ai.config.AiProperties;
import com.im.ai.config.ImAiProperties;
import com.im.ai.dto.req.InterviewChatRequest;
import com.im.ai.rag.KnowledgeBaseService;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 面试官对话服务。
 *
 * <p>一轮对话的组装流程：
 * <ol>
 *   <li>校验开关与消息序列（非空时最后一条必须是候选人发言）；</li>
 *   <li>用候选人最后一条消息去知识库做 BM25 检索，取 topK 片段；</li>
 *   <li>拼系统提示词：面试官人设与规则（内置）+ 本轮检索到的知识库片段；</li>
 *   <li>「system + 裁剪后的历史」交给 DashScope 流式生成，增量逐条推给前端 SSE。</li>
 * </ol>
 *
 * <p>检索按轮做而不是会话级一次：面试是渐进深挖的，候选人第 10 分钟谈到的
 * 「转码队列」和第 1 分钟谈的「整体架构」需要的知识片段完全不同。
 *
 * <p>推流跑在虚拟线程上（{@code ofLines} 是阻塞读），SseEmitter 立即返回给
 * Tomcat，不占用容器线程等待模型响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final ImAiProperties aiProperties;
    private final AiProperties modelProperties;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DashScopeChatClient chatClient;
    private final JsonUtil jsonUtil;

    /** 推流线程池：每个 SSE 请求一条虚拟线程，阻塞读上游不心疼 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }

    /**
     * 面试官人设与规则（后端 Java 全栈面试）。
     * 面试方法论沿用 md/待开发功能.txt 中约定的循序渐进/追问/打分规则，
     * 考察内容从「IM 文件协作平台专项」改为 Java 全栈通用知识体系，
     * 知识库检索片段作为命题的优先素材（有则结合、无则按考察范围自行命题）。
     */
    private static final String INTERVIEWER_PERSONA = """
            你现在是资深后端 Java 全栈面试官，正在对候选人进行一场「后端 Java 全栈面试」。
            考察范围（结合知识库内容，覆盖以下方向）：
            - Java 基础与 JVM：集合原理、并发编程（JUC、线程池、锁）、内存模型、GC 与调优
            - 框架：Spring / Spring Boot / MyBatis 的核心原理与实战问题（IoC/AOP、事务失效、循环依赖等）
            - 存储：MySQL 索引与事务、锁与隔离级别；Redis 缓存设计常见问题（穿透/击穿/雪崩/一致性）
            - 分布式与高并发：消息队列、分布式锁、分库分表、服务拆分、接口幂等与限流
            - 安全与稳定性：XSS/CSRF/越权防护、异常与降级、性能瓶颈定位、灾备
            - 全栈视角：前端工程化（Vue/JS）与前后端协作、部署运维基础
            面试规则：
            1. 每次只提 1 个问题，不要一次性抛出一堆问题；循序渐进，先了解项目经历与技术栈，再逐方向深挖。
            2. 候选人回答后，评估回答优缺点，针对薄弱点继续追问；方案有风险点时指出来并继续深入。
            3. 禁止直接给完整答案；可以给出方向性提示引导候选人自己想到。
            4. 候选人问题目细节时可以澄清题意，但不要主动扩展考察范围。
            5. 知识库中有与当前话题相关的内容时，优先结合知识库片段命题与追问；知识库没有则按上面的考察范围自行命题。
            6. 面试结束后，对候选人整体表现打分（各维度 + 总分）并总结。
            补充约定：
            - 对话历史为空时视为面试刚开始：先请候选人简要介绍一个他最熟悉的项目（业务背景、技术选型、自己负责的部分），随后从中选择切入点开始深挖。
            - 回答使用中文，语气专业、克制，不使用表情符号。""";

    /**
     * 异步执行一轮流式对话。参数校验在调用线程完成，
     * 这里进入虚拟线程后不再有 Servlet 上下文可用。
     */
    public void chatAsync(List<InterviewChatRequest.Message> messages, SseEmitter emitter) {
        if (!aiProperties.isEnabled()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "AI 面试官未启用");
        }
        List<Map<String, String>> history = sanitize(messages);
        streamExecutor.execute(() -> doChat(history, emitter));
    }

    /** 知识库与模型概况，前端状态条展示用 */
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>(knowledgeBaseService.status());
        status.put("enabled", aiProperties.isEnabled());
        status.put("apiKeyConfigured", modelProperties.getApiKey() != null && !modelProperties.getApiKey().isBlank());
        status.put("model", modelProperties.getChat().getOptions().getModel());
        return status;
    }

    /* ==================== 内部实现 ==================== */

    private void doChat(List<Map<String, String>> history, SseEmitter emitter) {
        try {
            String query = history.isEmpty() ? "开始面试 项目经历 技术选型 Java 并发 MySQL Redis 分布式"
                    : history.get(history.size() - 1).get("content");
            String systemPrompt = buildSystemPrompt(query);

            List<Map<String, String>> payload = new ArrayList<>();
            payload.add(Map.of("role", "system", "content", systemPrompt));
            payload.addAll(history);

            chatClient.streamChat(payload, delta -> sendDelta(emitter, delta));
            emitter.send(SseEmitter.event().name("done").data("{}"));
            emitter.complete();
        } catch (ClientAbortException e) {
            // 浏览器已断开（用户点了停止或关页面），静默收尾，不再读上游
            log.debug("AI 面试对话被客户端中断");
            emitter.complete();
        } catch (BusinessException e) {
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.warn("AI 面试对话失败: {}", e.getMessage(), e);
            sendError(emitter, "AI 服务异常，请稍后重试");
        }
    }

    private String buildSystemPrompt(String query) {
        List<KnowledgeBaseService.Chunk> chunks =
                knowledgeBaseService.retrieve(query, aiProperties.getRetrieveTopK());
        StringBuilder knowledge = new StringBuilder();
        int budget = aiProperties.getMaxKnowledgeChars();
        for (KnowledgeBaseService.Chunk chunk : chunks) {
            String text = chunk.text().length() > 1200 ? chunk.text().substring(0, 1200) + "…" : chunk.text();
            if (knowledge.length() + text.length() > budget) {
                break;
            }
            knowledge.append("\n【来源：").append(chunk.fileName()).append("】\n").append(text).append('\n');
        }
        String knowledgeSection = chunks.isEmpty()
                ? "（知识库暂无内容或本轮未检索到相关片段。此时按考察范围中的 Java 全栈知识体系自行命题，不要虚构知识库中不存在的内容。）"
                : knowledge.toString();
        return INTERVIEWER_PERSONA + "\n\n【知识库检索片段】\n" + knowledgeSection;
    }

    /**
     * 历史消息清洗：条数取最近 maxHistory 条，单条超长截断；
     * 非空时最后一条必须是 user——否则模型没有可回答的对象，还会白白消耗一次调用。
     */
    private List<Map<String, String>> sanitize(List<InterviewChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (!"user".equals(messages.get(messages.size() - 1).getRole())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "最后一条消息必须是候选人发言");
        }
        List<InterviewChatRequest.Message> recent = messages.size() > aiProperties.getMaxHistory()
                ? messages.subList(messages.size() - aiProperties.getMaxHistory(), messages.size())
                : messages;
        List<Map<String, String>> history = new ArrayList<>(recent.size());
        for (InterviewChatRequest.Message message : recent) {
            String content = message.getContent();
            if (content.length() > aiProperties.getMaxMessageChars()) {
                content = content.substring(0, aiProperties.getMaxMessageChars()) + "…";
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.getRole());
            item.put("content", content);
            history.add(item);
        }
        return history;
    }

    private void sendDelta(SseEmitter emitter, String delta) {
        try {
            // 增量文本包成单行 JSON 再发：SSE 的 data 字段按行拆分，
            // 裸文本里的换行会让前端把一条增量误解析成多个事件
            emitter.send(SseEmitter.event().name("delta")
                    .data(jsonUtil.toJson(Map.of("content", delta))));
        } catch (IOException | IllegalStateException e) {
            // emitter 已完成/客户端断开，终止上游读取
            throw new ClientAbortException(e);
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(jsonUtil.toJson(Map.of("message", message == null ? "AI 服务异常" : message))));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    /** 客户端断开标记异常，仅用于跳出上游 SSE 读取循环 */
    private static class ClientAbortException extends RuntimeException {
        ClientAbortException(Throwable cause) {
            super(cause);
        }
    }
}
