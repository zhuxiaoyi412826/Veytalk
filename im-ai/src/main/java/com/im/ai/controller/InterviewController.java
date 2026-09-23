package com.im.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.ai.dto.req.InterviewChatRequest;
import com.im.ai.service.InterviewService;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * AI 面试官接口。
 *
 * <p>/chat 返回 SSE 流而不是 Result JSON：模型逐 token 生成，
 * 等全文再返回意味着候选人盯着空屏等十几秒，流式是唯一可接受的交互。
 * 进入流之前的失败（未启用、参数错误、限流）仍走全局异常处理器的
 * HTTP 200 + Result JSON，前端按 Content-Type 区分两条路径。
 *
 * <p>限流按用户 20 次/分钟：每次调用都是一次真实的大模型计费请求，
 * 比其余接口更需要护栏；正常面试节奏（读题+思考+作答）远低于这个频率。
 */
@Tag(name = "08-后端 Java 全栈面试", description = "基于知识库 RAG 的智能面试对话")
@RestController
@RequestMapping("/api/ai/interview")
@RequiredArgsConstructor
@SaCheckLogin
public class InterviewController {

    private final InterviewService interviewService;

    @Operation(summary = "面试对话（SSE 流式）",
            description = "请求体携带完整对话历史（空数组=开始新面试）；响应为 text/event-stream，"
                    + "事件：delta（增量文本 JSON）、done（本轮结束）、error（失败原因 JSON）")
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "ai.interview.chat")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Valid InterviewChatRequest request) {
        // 5 分钟：给「模型排队 + 长回答」留足余量，超时会触发 onTimeout 由容器收尾
        SseEmitter emitter = new SseEmitter(300_000L);
        interviewService.chatAsync(request.getMessages(), emitter);
        return emitter;
    }

    @Operation(summary = "面试官状态",
            description = "返回知识库目录、文件数、片段数与模型配置概况，前端状态条展示用")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(interviewService.status());
    }
}
