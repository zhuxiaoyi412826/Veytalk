package com.im.nativeapp.ui.call

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.im.nativeapp.core.call.CallManager
import com.im.nativeapp.core.call.WebRtcSupport
import org.webrtc.MediaStream
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 全屏通话浮层（对齐 im-ui/src/components/CallOverlay.vue 的信息结构）：
 * 远端画面铺满 + 本端小窗右上角 + 顶部对方名/状态计时 + 底部控制排。
 * 挂在 MainActivity 的根 Box 上，CallManager.state.visible 时盖在一切路由之上。
 */
@Composable
fun CallOverlay(manager: CallManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            manager.accept()
        } else {
            Toast.makeText(context, "未授予麦克风/摄像头权限，无法接听", Toast.LENGTH_SHORT).show()
            manager.reject()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
    ) {
        // 远端画面铺满；语音通话无远端流，只显示头像占位
        val remote = state.remoteStream
        if (state.mediaType == "video") {
            VideoSurface(
                stream = remote,
                mirror = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 顶部：对方名 + 状态/计时
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.peerName, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            val statusText = when (state.status) {
                CallManager.Status.DIALING -> state.hint.ifEmpty { "正在呼叫…" }
                CallManager.Status.RINGING -> state.hint.ifEmpty { "来电邀请" }
                CallManager.Status.ACTIVE ->
                    if (state.remoteStream == null && state.mediaType == "video")
                        state.hint.ifEmpty { "等待对方画面…" }
                    else state.hint.ifEmpty { formatDuration(state.seconds) }
                else -> state.hint
            }
            Text(statusText, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }

        // 本端小窗（镜像）
        if (state.mediaType == "video" && state.localStream != null && state.status == CallManager.Status.ACTIVE) {
            VideoSurface(
                stream = state.localStream,
                mirror = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 110.dp, end = 16.dp)
                    .size(110.dp, 160.dp)
                    .background(Color.Black, RoundedCornerShape(12.dp))
            )
        }

        // 底部控制排
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state.status) {
                CallManager.Status.RINGING -> {
                    CallCircleButton(Color(0xFF3DD575), Icons.Default.Call, "接听") {
                        val wanted = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (state.mediaType == "video") add(Manifest.permission.CAMERA)
                        }
                        permissionLauncher.launch(wanted.toTypedArray())
                    }
                    CallCircleButton(Color(0xFFE5484D), Icons.Default.CallEnd, "拒绝") { manager.reject() }
                }
                else -> {
                    // 静音
                    CallCircleButton(
                        if (state.muted) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.18f),
                        if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        if (state.muted) "已静音" else "静音"
                    ) { manager.toggleMute() }
                    // 摄像头（仅视频）
                    if (state.mediaType == "video") {
                        CallCircleButton(
                            if (state.cameraOff) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.18f),
                            if (state.cameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            if (state.cameraOff) "已关闭" else "摄像头"
                        ) { manager.toggleCamera() }
                    }
                    // 扬声器
                    CallCircleButton(
                        if (state.speaker) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.18f),
                        Icons.AutoMirrored.Filled.VolumeUp,
                        "扬声器"
                    ) { manager.toggleSpeaker() }
                    // 挂断/取消
                    CallCircleButton(Color(0xFFE5484D), Icons.Default.CallEnd, if (state.status == CallManager.Status.DIALING) "取消" else "挂断") {
                        if (state.status == CallManager.Status.DIALING) manager.cancel() else manager.hangup()
                    }
                }
            }
        }
    }
}

@Composable
private fun CallCircleButton(
    bg: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = bg,
            modifier = Modifier.size(64.dp)
        ) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * WebRTC 视频渲染：SurfaceViewRenderer 常驻 remember，流变化只做 addSink/removeSink，
 * 避免 AndroidView.update 高频重绑导致画面叠加。
 */
@Composable
private fun VideoSurface(stream: MediaStream?, mirror: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(WebRtcSupport.eglBaseContext, null)
            setMirror(mirror)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            renderer.clearImage()
            renderer.release()
        }
    }
    DisposableEffect(stream) {
        val track = stream?.videoTracks?.firstOrNull() as? VideoTrack
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
