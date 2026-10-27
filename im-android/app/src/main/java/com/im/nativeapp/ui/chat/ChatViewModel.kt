package com.im.nativeapp.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.call.CallManager
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.media.ImageOcr
import com.im.nativeapp.core.media.VoicePlayer
import com.im.nativeapp.core.media.VoiceRecorder
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.FileApi
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.data.ConversationRepository
import com.im.nativeapp.data.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val session: SessionStore,
    private val apiClient: ApiClient,
    private val callManager: CallManager,
    private val imageOcr: ImageOcr,
    val recorder: VoiceRecorder,
    private val voicePlayer: VoicePlayer
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    /** 正在播放的语音消息 ID，气泡据此高亮播放按钮 */
    val voicePlayingId = voicePlayer.playingId

    fun playVoice(messageId: String, url: String) = voicePlayer.play(messageId, url)

    /** 发起 1:1 通话（单聊才有入口）：对端用户 ID 取会话的 targetId */
    fun dialCall(mediaType: String) {
        val conv = conversation.value ?: return
        val peer = conv.targetId ?: return
        callManager.dial(peer, mediaType, conversationId, conv.name ?: "对方")
    }

    /** 会话资料跟随仓库列表实时更新（在线状态/未读变化会反映到顶栏） */
    val conversation: StateFlow<com.im.nativeapp.core.network.ConversationVO?> =
        conversations.list
            .map { list -> list.find { it.conversationId == conversationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messageList: StateFlow<List<MessageVO>> = messages.messagesOf(conversationId)
    val hasMore: StateFlow<Boolean> = messages.hasMoreOf(conversationId)

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast

    /** 上传中的附件占位提示（简单 toast 即可，上传链路失败由气泡标红呈现） */
    private val _sendingAttachment = MutableStateFlow(false)
    val sendingAttachment: StateFlow<Boolean> = _sendingAttachment

    init {
        conversations.setActive(conversationId)
        viewModelScope.launch {
            // 本地优先渲染，再拉服务端最新一页合并（对齐 chat.js loadHistory(reset)）
            runCatching { messages.loadHistory(conversationId, reset = true) }
            // 进会话即已读：用当前最大 seq 上报（对齐 ChatWindow 的 markRead 时机）
            val maxSeq = messageList.value.mapNotNull { it.seq?.toLongOrNull() }.maxOrNull()?.toString()
            conversations.markRead(conversationId, maxSeq)
            if (maxSeq != null) runCatching { messages.reportRead(conversationId, maxSeq) }
        }
    }

    override fun onCleared() {
        conversations.setActive(null)
        voicePlayer.stop()
        recorder.cancel()
    }

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    /**
     * 下载文件消息：换带短时票据的直链交给系统下载管理器。
     * DownloadManager 不能自定义鉴权头，走 /file/{id}/url 票据直链（对齐 Web 端 resolveDirectDownloadUrl）。
     */
    fun downloadFile(msg: MessageVO) {
        val fileId = msg.extra?.fileId ?: msg.content
        if (fileId.isNullOrBlank()) return
        val fileName = msg.extra?.fileName ?: "file-${fileId}"
        viewModelScope.launch {
            try {
                val url: String = apiClient.service(FileApi::class.java)
                    .signedUrl(fileId)
                    .unwrap(apiClient.json)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val request = android.app.DownloadManager.Request(Uri.parse(url))
                    .setTitle(fileName)
                    .setDescription("IM 文件下载")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                dm.enqueue(request)
                _toast.tryEmit("已开始下载：$fileName")
            } catch (e: Exception) {
                _toast.tryEmit(if (e is ApiError) e.message ?: "下载失败" else "下载失败：${e.message ?: ""}")
            }
        }
    }

    fun loadMore() {
        viewModelScope.launch { runCatching { messages.loadHistory(conversationId, reset = false) } }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            messages.sendText(conversationId, text.trim())
                .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
        }
    }

    fun sendImage(uri: Uri) {
        viewModelScope.launch {
            _sendingAttachment.value = true
            try {
                // width/height 服务端不回填，客户端自己读（对齐 message.js 的注释，否则气泡缩略图跳动）
                val size = readImageSize(uri)
                // 发送端本地 OCR（对齐 Web 端 ChatWindow：识别结果随消息上传，服务端过敏感词后存 extra.ocrText）。
                // 任何异常返回 null 照常发送，不阻断主流程。
                val ocrText = imageOcr.recognize(uri)
                messages.uploadFile(uri, "chat_image")
                    .onSuccess { file ->
                        messages.sendAttachment(conversationId, 2, file, width = size?.first, height = size?.second, ocrText = ocrText)
                            .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
                    }
                    .onFailure { e -> _toast.tryEmit("图片上传失败：${e.message ?: ""}") }
            } finally {
                _sendingAttachment.value = false
            }
        }
    }

    fun sendFile(uri: Uri) {
        viewModelScope.launch {
            _sendingAttachment.value = true
            try {
                messages.uploadFile(uri, "chat_file")
                    .onSuccess { file ->
                        messages.sendAttachment(conversationId, 3, file)
                            .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
                    }
                    .onFailure { e -> _toast.tryEmit("文件上传失败：${e.message ?: ""}") }
            } finally {
                _sendingAttachment.value = false
            }
        }
    }

    fun sendVoice(path: String, duration: Int) {
        viewModelScope.launch {
            _sendingAttachment.value = true
            try {
                messages.uploadVoiceFile(path, duration)
                    .onSuccess { file ->
                        messages.sendAttachment(conversationId, 4, file, duration = duration)
                            .onFailure { e -> _toast.tryEmit(e.message ?: "发送失败") }
                        runCatching { java.io.File(path).delete() }
                    }
                    .onFailure { e -> _toast.tryEmit("语音上传失败：${e.message ?: ""}") }
            } finally {
                _sendingAttachment.value = false
            }
        }
    }

    private fun readImageSize(uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()

    fun recall(message: MessageVO) {
        val mid = message.messageId ?: return
        viewModelScope.launch {
            messages.recall(conversationId, mid)
                .onFailure { e -> _toast.tryEmit(e.message ?: "撤回失败") }
        }
    }

    fun resend(message: MessageVO) {
        viewModelScope.launch {
            messages.resend(conversationId, message)
                .onFailure { e -> _toast.tryEmit(e.message ?: "重发失败") }
        }
    }

    fun discard(message: MessageVO) {
        message.clientMsgId?.let { messages.discard(conversationId, it) }
    }

    fun deleteMessage(message: MessageVO) {
        val mid = message.messageId ?: return
        viewModelScope.launch {
            messages.deleteLocal(conversationId, mid)
                .onFailure { e -> _toast.tryEmit(e.message ?: "删除失败") }
        }
    }
}
