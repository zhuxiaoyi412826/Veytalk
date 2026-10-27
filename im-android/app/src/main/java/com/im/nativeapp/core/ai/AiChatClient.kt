package com.im.nativeapp.core.ai

import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 面试对话 SSE 流式客户端（对齐 im-ui/src/api/ai.js streamInterviewChat）。
 *
 * /chat 是 SSE 流不是 Result JSON，不能走 Retrofit（等整个响应收完才回调），
 * 这里用 OkHttp 同步流式读，手工按 \n\n 分块解析事件：
 * delta（增量文本 JSON {content}）、done（本轮结束）、error（失败原因 JSON {message}）。
 * 进入流之前的失败（未登录/限流/参数错误）后端按 Result JSON 返回，按 Content-Type 分流。
 */
@Singleton
class AiChatClient @Inject constructor(
    private val apiClient: ApiClient,
    private val session: SessionStore
) {
    class AiException(message: String) : Exception(message)

    /**
     * 发起一轮流式对话。
     * @param messages 完整对话历史 [{role, content}]，空数组 = 开始新面试（AI 主动出题）
     * @param onDelta 每收到一段增量文本回调一次（主线程切换由调用方负责）
     * 正常结束（done）返回；error 事件/传输失败抛 AiException。
     */
    suspend fun chat(messages: List<Pair<String, String>>, onDelta: (String) -> Unit) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                messages.forEach { (role, content) ->
                    add(buildJsonObject { put("role", role); put("content", content) })
                }
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${session.apiBase()}/ai/interview/chat")
            .post(body)
            .build()

        apiClient.mediaHttpClient.newCall(request).execute().use { response ->
            val contentType = response.header("Content-Type") ?: ""
            if (contentType.contains("application/json")) {
                // 进流之前的失败：统一 Result JSON
                val text = response.body?.string().orEmpty()
                val msg = runCatching {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    (obj["message"] ?: obj["msg"])?.jsonPrimitive?.content
                }.getOrNull()
                throw AiException(msg ?: "请求失败（HTTP ${response.code}）")
            }
            if (!response.isSuccessful) throw AiException("AI 服务异常（HTTP ${response.code}）")
            val source = response.body?.source() ?: throw AiException("AI 服务异常（空响应）")

            // 逐行读，空行（\n\n 分块边界）触发一次事件解析
            var event = "message"
            val dataLines = StringBuilder()
            fun dispatch(): Boolean {
                val data = dataLines.toString().trim()
                dataLines.clear()
                val currentEvent = event
                event = "message"
                if (data.isEmpty()) return false
                when (currentEvent) {
                    "delta" -> {
                        val content = runCatching {
                            Json.parseToJsonElement(data).jsonObject["content"]?.jsonPrimitive?.content
                        }.getOrNull()
                        if (!content.isNullOrEmpty()) onDelta(content)
                    }
                    "error" -> {
                        val msg = runCatching {
                            Json.parseToJsonElement(data).jsonObject["message"]?.jsonPrimitive?.content
                        }.getOrNull()
                        throw AiException(msg ?: "AI 服务异常")
                    }
                }
                return currentEvent == "done"
            }

            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> if (dispatch()) return@withContext
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> dataLines.append(line.substring(5).trim()).append('\n')
                }
            }
            // 流自然结束（服务端可能省 done 直接断流）：残余半包尝试派发一次
            if (dataLines.isNotEmpty()) dispatch()
        }
    }
}
