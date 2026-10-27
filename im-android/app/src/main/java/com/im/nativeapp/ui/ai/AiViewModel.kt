package com.im.nativeapp.ui.ai

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.ai.AiChatClient
import com.im.nativeapp.core.media.PcmRecorder
import com.im.nativeapp.core.network.AiApi
import com.im.nativeapp.core.network.ApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject

/**
 * AI 面试助手（对齐 im-ui 面试页 + api/ai.js 的对话/ASR/TTS 链路）。
 * 对话走 /ai/interview/chat SSE 流；语音输入走 /asr（16k WAV）；播报走 /tts（audio/wav）。
 * 面试监考事件（切屏/分辨率上报）是桌面 Web 语义，移动端不做。
 */
@HiltViewModel
class AiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
    private val chatClient: AiChatClient,
    val pcmRecorder: PcmRecorder
) : ViewModel() {

    data class ChatItem(
        val key: Long,
        val role: String,           // user / assistant
        val content: String,
        val streaming: Boolean = false
    )

    private val api get() = apiClient.service(AiApi::class.java)

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _transcribing = MutableStateFlow(false)
    val transcribing: StateFlow<Boolean> = _transcribing

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint

    /** 回答完成自动播报开关 */
    private val _autoSpeak = MutableStateFlow(false)
    val autoSpeak: StateFlow<Boolean> = _autoSpeak

    private var seq = 0L
    private var player: MediaPlayer? = null
    private var started = false

    /** 进入页面：拉状态条 + 空历史自动开场（AI 出第一题） */
    fun enter() {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching {
                val env = api.status()
                env.data?.jsonObject?.get("model")?.jsonPrimitive?.content?.let { _modelName.value = it }
            }
            if (_messages.value.isEmpty()) streamRound()
        }
    }

    fun toggleSpeak() {
        _autoSpeak.value = !_autoSpeak.value
        if (!_autoSpeak.value) stopSpeak()
    }

    /** 发送一条用户消息并流式取回答 */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _generating.value) return
        _messages.value = _messages.value + ChatItem(++seq, "user", t)
        streamRound()
    }

    /** 用当前完整历史（含刚追加的用户消息）发起一轮 SSE */
    private fun streamRound() {
        if (_generating.value) return
        _generating.value = true
        val history = _messages.value.filter { !it.streaming }.map { it.role to it.content }
        val assistantKey = ++seq
        _messages.value = _messages.value + ChatItem(assistantKey, "assistant", "", streaming = true)
        viewModelScope.launch {
            try {
                chatClient.chat(history) { delta ->
                    appendDelta(assistantKey, delta)
                }
                val finalText = _messages.value.firstOrNull { it.key == assistantKey }?.content.orEmpty()
                markDone(assistantKey)
                if (_autoSpeak.value && finalText.isNotBlank()) speak(finalText)
            } catch (e: Exception) {
                markDone(assistantKey)
                _hint.value = e.message ?: "AI 服务异常"
            } finally {
                _generating.value = false
            }
        }
    }

    private fun appendDelta(key: Long, delta: String) {
        _messages.value = _messages.value.map {
            if (it.key == key) it.copy(content = it.content + delta) else it
        }
    }

    private fun markDone(key: Long) {
        _messages.value = _messages.value.map { if (it.key == key) it.copy(streaming = false) else it }
    }

    /* ------------------------------ 语音输入（ASR） ------------------------------ */

    fun startVoice() {
        if (_generating.value || _recording.value) return
        val path = pcmRecorder.start() ?: run {
            _hint.value = "录音启动失败，请检查麦克风权限"
            return
        }
        _recording.value = true
        _recording.value = path.isNotEmpty()
    }

    /** 松开：停录 → 上传 ASR → 识别文本直接作为回答发送 */
    fun finishVoice() {
        if (!_recording.value) return
        _recording.value = false
        viewModelScope.launch {
            val path = pcmRecorder.stop() ?: return@launch
            _transcribing.value = true
            try {
                val env = withContext(Dispatchers.IO) {
                    val file = File(path)
                    api.asr(
                        MultipartBody.Part.createFormData(
                            "file", "speech.wav",
                            file.asRequestBody("audio/wav".toMediaType())
                        )
                    )
                }
                val text = env.data?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
                if (text.isBlank()) _hint.value = "没听清，请再说一次" else send(text)
            } catch (e: Exception) {
                _hint.value = "语音转写失败"
            } finally {
                _transcribing.value = false
                runCatching { File(path).delete() }
            }
        }
    }

    fun cancelVoice() {
        if (!_recording.value) return
        _recording.value = false
        viewModelScope.launch { pcmRecorder.cancel() }
    }

    /* ------------------------------ 语音播报（TTS） ------------------------------ */

    /** 手动播报某条回答（或自动播报）。成功体是 wav 字节流，失败体是 Result JSON。 */
    fun speak(text: String) {
        viewModelScope.launch {
            try {
                val body = withContext(Dispatchers.IO) { api.tts(mapOf("text" to text.take(500))) }
                val contentType = body.contentType()?.toString() ?: ""
                if (contentType.contains("application/json")) {
                    val msg = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(body.string())
                            .jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull()
                    _hint.value = msg ?: "语音合成失败"
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                playWav(file)
            } catch (e: Exception) {
                _hint.value = "语音合成失败"
            }
        }
    }

    private fun playWav(file: File) {
        stopSpeak()
        val p = MediaPlayer()
        player = p
        runCatching {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                runCatching { file.delete() }
            }
            p.prepare()
            p.start()
        }.onFailure {
            runCatching { p.release() }
            player = null
        }
    }

    fun stopSpeak() {
        player?.let { runCatching { if (it.isPlaying) it.stop(); it.release() } }
        player = null
    }

    fun consumeHint() {
        _hint.value = null
    }

    override fun onCleared() {
        stopSpeak()
        viewModelScope.launch { pcmRecorder.cancel() }
    }
}
