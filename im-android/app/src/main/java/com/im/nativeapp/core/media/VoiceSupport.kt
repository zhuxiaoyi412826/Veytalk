package com.im.nativeapp.core.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.im.nativeapp.core.network.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音录制（MediaRecorder AAC/m4a，输出到 app 私有目录）。
 * 录音文件只活在上传前：发送成功后由调用方删除。
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startAt: Long = 0

    /** 开始录音，返回输出文件路径；已在录音时返回 null */
    fun start(): String? {
        if (recorder != null) return null
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            startAt = System.currentTimeMillis()
            file.absolutePath
        } catch (e: Exception) {
            Log.w("VoiceRecorder", "start failed", e)
            runCatching { r.release() }
            null
        }
    }

    /** 停止并返回 (路径, 时长秒)；时长不足 1 秒视为误触返回 null */
    fun stop(): Pair<String, Int>? {
        val r = recorder ?: return null
        recorder = null
        val seconds = ((System.currentTimeMillis() - startAt) / 1000L).toInt()
        runCatching { r.stop() }
        runCatching { r.release() }
        val path = outputFile?.absolutePath ?: return null
        return if (seconds >= 1) path to seconds else {
            runCatching { File(path).delete() }
            null
        }
    }

    /** 取消录音并丢弃文件 */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
    }
}

/**
 * 语音播放（ExoPlayer + 带鉴权头的 OkHttp 数据源）。
 * 全局单实例：IM 场景同时只播一条语音，播新的一条自动停掉上一条。
 */
@Singleton
class VoicePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    apiClient: ApiClient
) {
    private val factory = OkHttpDataSource.Factory(apiClient.mediaHttpClient)
    private var player: ExoPlayer? = null

    /** 正在播放的消息 ID，UI 据此画播放动画/高亮 */
    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId

    fun play(messageId: String, url: String) {
        stop()
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(factory)
            )
            .build()
        player = p
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.play()
        _playingId.value = messageId
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED || state == androidx.media3.common.Player.STATE_IDLE) {
                    if (_playingId.value == messageId) _playingId.value = null
                }
            }
        })
    }

    fun stop() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        _playingId.value = null
    }
}
