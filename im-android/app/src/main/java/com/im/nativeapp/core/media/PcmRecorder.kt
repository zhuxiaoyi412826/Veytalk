package com.im.nativeapp.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 专用 PCM 录音器：16kHz 单声道 PCM16，停止时写成标准 WAV。
 *
 * 与语音消息的 VoiceRecorder（44.1k AAC/m4a）不同：后端 /ai/interview/asr
 * 只收 16k 单声道 WAV（对齐 Web 端 utils/audioRec.js 的自编码格式），必须单独一路。
 */
@Singleton
class PcmRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PcmRecorder"
        const val SAMPLE_RATE = 16_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var job: Job? = null
    private var outputFile: File? = null
    private var startAt: Long = 0

    /** 开始录音；已在录音返回 null。权限由调用方（UI 层）先行申请。 */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (record != null) return null
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE) // 至少 1 秒缓冲
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return null
        }
        val file = File(context.cacheDir, "asr_${System.currentTimeMillis()}.wav")
        return try {
            r.startRecording()
            record = r
            outputFile = file
            startAt = System.currentTimeMillis()
            job = scope.launch {
                FileOutputStream(file).use { fos ->
                    fos.write(ByteArray(44)) // WAV 头占位，停止时回填
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) fos.write(buf, 0, n) else break
                    }
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            record = null
            runCatching { r.release() }
            null
        }
    }

    /** 停止并回填 WAV 头，返回文件路径；时长不足 0.5 秒视为误触返回 null */
    suspend fun stop(): String? {
        val r = record ?: return null
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
        job?.join()
        job = null
        val path = outputFile?.absolutePath ?: return null
        val seconds = (System.currentTimeMillis() - startAt) / 1000.0
        if (seconds < 0.5) {
            runCatching { File(path).delete() }
            return null
        }
        runCatching { writeWavHeader(path) }
        return path
    }

    suspend fun cancel() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
        job?.join()
        job = null
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
    }

    /** 标准 44 字节 RIFF/WAV 头（PCM16 单声道） */
    private fun writeWavHeader(path: String) {
        val f = File(path)
        val dataLen = f.length() - 44
        if (dataLen <= 0) return
        val byteRate = SAMPLE_RATE * 2 // 16bit mono
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes((36 + dataLen).toInt()))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16))
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // mono
            raf.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            raf.writeInt(Integer.reverseBytes(byteRate))
            raf.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt()) // blockAlign
            raf.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt()) // bits
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(dataLen.toInt()))
        }
    }
}
