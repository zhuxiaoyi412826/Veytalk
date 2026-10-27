package com.im.nativeapp.core.media

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 图内文字 OCR（ML Kit 端侧中文识别，替代 Web 端 tesseract.js 方案）。
 *
 * 与 Web 端同一约定：识别结果随图片消息上传 extra.ocrText，服务端过敏感词遮蔽后存储；
 * 任何异常（模型未下载/解码失败/识别为空）都返回 null 照常发送，OCR 是旁路能力不阻断主流程。
 * 模型由 Play Services 按需下发，首次识别可能触发下载而较慢。
 */
@Singleton
class ImageOcr @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 与服务端 AttachmentContentHandler.OCR_TEXT_MAX_LENGTH 对齐 */
    private val maxLength = 2000

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别图片文字；失败或无文字返回 null。结果压掉碎片空白后截断。 */
    suspend fun recognize(uri: Uri): String? = suspendCancellableCoroutine { cont ->
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { if (it.length > maxLength) it.substring(0, maxLength) else it }
                cont.resume(text)
            }
            .addOnFailureListener { cont.resume(null) }
    }
}
