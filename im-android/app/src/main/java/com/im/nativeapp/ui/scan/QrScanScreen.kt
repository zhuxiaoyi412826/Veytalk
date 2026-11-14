package com.im.nativeapp.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.im.nativeapp.ui.components.Avatar
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 登录二维码识别规则（对齐《APP 扫码登录与文件传输助手对接指南》1.2）。
 *
 * 只认「路径落在 /transfer 或 /filehelper + query 带 scene」的链接：scene 是对方网页这次登录会话的
 * 唯一标识，也是安全密钥。放宽到「任意带 scene 的二维码」等于允许第三方用自家二维码诱导授权。
 */
object QrLoginLink {
    fun sceneOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        // 只允许 http(s) 或无 scheme 的裸地址；其它 scheme（smb:// / file://）一律不当登录码
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return null
        val lastSegment = uri.pathSegments?.lastOrNull()?.lowercase() ?: return null
        if (lastSegment != "transfer" && lastSegment != "filehelper") return null
        return uri.getQueryParameter("scene")?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * 扫一扫（扫码登录网页）。
 *
 * 交互与安全口径：
 * - 扫到登录码后先调 scanned，让网页从「待扫码」变「待手机确认」；
 * - 必须用户显式点「确认登录」才调 authorize，禁止扫到即静默授权；
 * - 取消就是不调 authorize，让对方二维码自己 120 秒后过期（后端没有拒绝接口）；
 * - 二维码过期一律回 1001，提示「让对方刷新」而不是笼统的失败。
 */
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    vm: QrScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val me by vm.me.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 命中即停帧：避免同一个码连发多次导致重复调 scanned / 反复弹确认框
    val paused = remember { mutableStateOf(false) }
    var confirmScene by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.message.collect {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            paused.value = false
        }
    }
    LaunchedEffect(Unit) {
        vm.authorized.collect {
            Toast.makeText(context, "授权成功，网页即将自动登录", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(paused = paused) { raw ->
                val scene = QrLoginLink.sceneOf(raw)
                if (scene == null) {
                    // 不是登录码：提示后继续扫，不打断相机
                    Toast.makeText(context, "未识别到本系统的登录二维码", Toast.LENGTH_SHORT).show()
                    paused.value = false
                } else {
                    confirmScene = scene
                    vm.markScanned(scene)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "扫一扫需要相机权限",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            }
        }

        // 顶部返回 + 标题
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text("扫一扫", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        if (granted) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "将电脑网页上的登录二维码放入框内",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    confirmScene?.let { scene ->
        val name = me.nickname.ifBlank { me.username }
        AlertDialog(
            onDismissRequest = {
                confirmScene = null
                paused.value = false
            },
            title = { Text("确认登录网页？") },
            text = {
                Column {
                    Text("已检测到网页登录请求，确认后将登录该网页。")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(url = vm.resolveAvatar(me.avatar), name = name, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "当前 APP 登录账号",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "不是你本人扫码？请取消，对方网页不会登录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { vm.authorize(scene) }
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("确认登录")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmScene = null
                        paused.value = false
                    }
                ) { Text("取消") }
            }
        )
    }
}

/* ------------------------------ 相机预览与二维码解码 ------------------------------ */

@Composable
private fun CameraPreview(paused: MutableState<Boolean>, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analyzer = remember {
        QrAnalyzer { raw ->
            if (!paused.value) {
                paused.value = true
                onQr(raw)
            }
        }
    }

    LaunchedEffect(Unit) {
        val provider = context.cameraProvider()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analyzer.executor, analyzer) }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }.onFailure {
            paused.value = true
            Toast.makeText(context, "相机启动失败：${it.message ?: ""}", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { analyzer.close() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private suspend fun android.content.Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this@cameraProvider)
        future.addListener({
            runCatching { future.get() }
                .onSuccess { cont.resume(it) }
                .onFailure { cont.resumeWithException(it) }
        }, ContextCompat.getMainExecutor(this@cameraProvider))
    }

/**
 * 每帧交给 ML Kit 端侧解码；扫描器与单线程执行器跟随这个页面存活，离开页面即释放。
 */
private class QrAnalyzer(private val onRaw: (String) -> Unit) : ImageAnalysis.Analyzer {

    val executor = Executors.newSingleThreadExecutor()

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let(onRaw) }
            .addOnCompleteListener { image.close() }
    }

    fun close() {
        scanner.close()
        executor.shutdown()
    }
}
