package com.im.nativeapp.core.call

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一对一通话的 WebRTC 引擎（对齐 im-ui/src/utils/webrtc.js 的 createCallPeer）。
 *
 * 只封装 PeerConnection 生命周期，不含业务状态：
 * - 采集：音频恒开并做回声/降噪/增益；视频按 mediaType 决定（前置 1280x720@30）
 * - 信令：offer/answer/ice 经 onLocalSignal 抛给上层（CallManager 走 WS type=call 上行）
 * - trickle 竞态：远端 candidate 先于 remoteDescription 到达时先入队，setRemote 后统一冲刷
 * - 一次通话一个实例，close 后即弃；每实例各自 CameraCapturer（1:1 无共享流需求）
 *
 * PeerConnectionFactory 全局单例：initialize 只能调一次，工厂创建成本高，全部通话复用。
 */
object WebRtcSupport {

    @Volatile private var initialized = false
    @Volatile private var factory: PeerConnectionFactory? = null

    val eglBaseContext: EglBase.Context by lazy { EglBase.create().eglBaseContext }

    @Synchronized
    fun factory(context: Context): PeerConnectionFactory {
        if (factory != null) return factory!!
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            initialized = true
        }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .createPeerConnectionFactory()
        return factory!!
    }
}

/** ICE 配置项（来自 /call/config 的 iceServers，urls 支持 string 或 list） */
data class IceServerCfg(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    fun toNative(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(urls)
        if (!username.isNullOrBlank()) builder.setUsername(username)
        if (!credential.isNullOrBlank()) builder.setPassword(credential)
        return builder.createIceServer()
    }
}

class CallEngine(
    context: Context,
    private val iceServers: List<IceServerCfg>,
    private val mediaType: String,
    private val onLocalSignal: (action: String, data: JsonObject) -> Unit,
    private val onLocalStream: (MediaStream) -> Unit,
    private val onRemoteStream: (MediaStream) -> Unit,
    private val onConnectionState: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val callFactory = WebRtcSupport.factory(context)
    private val egl = WebRtcSupport.eglBaseContext

    private var pc: PeerConnection? = null
    private var remoteStream: MediaStream? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var ownsStream = false
    private var localStreamRef: MediaStream? = null

    /** 远端 candidate 早于 remoteDescription 到达时暂存，setRemote 后冲刷（对齐 pendingCandidates） */
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var closed = false

    private fun ensurePc(): PeerConnection {
        pc?.let { return it }
        if (closed) throw IllegalStateException("通话已结束")
        val config = PeerConnection.RTCConfiguration(iceServers.map { it.toNative() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // candidate 为 null 的收集结束事件在 native 回调里不会出现，无需判空
                onLocalSignal("ice", buildJsonObject {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid ?: "0")
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                })
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                // stream-webrtc-android 的 RtpReceiver 不一定暴露 getStreams()，自维护远端流容器：
                // MediaStream 只是轨道的命名容器，把收到的音/视频轨挂进去再抛给上层（等价 event.streams[0]）
                val track = transceiver.receiver.track() ?: return
                val stream = remoteStream ?: callFactory.createLocalMediaStream("remote").also { remoteStream = it }
                when (track) {
                    is VideoTrack -> stream.addTrack(track)
                    is AudioTrack -> stream.addTrack(track)
                }
                onRemoteStream(stream)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onConnectionState(newState.toString().lowercase().substringAfterLast('.'))
            }

            // 以下为不需要处理的回调
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }
        pc = callFactory.createPeerConnection(config, observer)
            ?: throw IllegalStateException("PeerConnection 创建失败")
        return pc!!
    }

    /** 采集本地音视频并挂到 pc（对齐 initMedia）；失败抛错由上层提示 */
    fun initMedia() {
        val stream = callFactory.createLocalMediaStream("local")
        // 音频恒开：回声消除/降噪/自动增益（与 Web 端 mediaConstraints 一致）
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        }
        audioSource = callFactory.createAudioSource(audioConstraints)
        localAudioTrack = callFactory.createAudioTrack("audio0", audioSource)
        stream.addTrack(localAudioTrack)

        if (mediaType == "video") {
            val enumerator = Camera2Enumerator(appContext)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: throw IllegalStateException("没有可用摄像头")
            val capturer0 = enumerator.createCapturer(name, null)
                ?: throw IllegalStateException("摄像头打开失败")
            capturer = capturer0
            videoSource = callFactory.createVideoSource(false)
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", egl)
            capturer0.initialize(surfaceHelper, appContext, videoSource!!.capturerObserver)
            capturer0.startCapture(1280, 720, 30)
            localVideoTrack = callFactory.createVideoTrack("video0", videoSource)
            stream.addTrack(localVideoTrack)
        }

        ownsStream = true
        localStreamRef = stream
        onLocalStream(stream)
        val conn = ensurePc()
        // Unified Plan 的 addTrack 签名是 (track, streamIds: List<String>)
        stream.videoTracks.forEach { conn.addTrack(it, listOf("local")) }
        stream.audioTracks.forEach { conn.addTrack(it, listOf("local")) }
    }

    /** 主叫在收到被叫 accept 后调用：生成 offer 并经信令发出 */
    suspend fun createOffer() {
        val conn = ensurePc()
        val desc = awaitSdp { conn.createOffer(it, MediaConstraints()) }
        awaitSet { conn.setLocalDescription(it, desc) }
        onLocalSignal("offer", buildJsonObject {
            put("sdpType", desc.type.canonicalForm())
            put("sdp", desc.description)
        })
    }

    /** 收到远端 offer/answer：落 remoteDescription 并冲刷排队 candidate；offer 时顺手 answer 回投 */
    suspend fun handleDescription(sdpType: String, sdp: String) {
        val conn = ensurePc()
        val desc = SessionDescription(
            if (sdpType == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
            sdp
        )
        awaitSet { conn.setRemoteDescription(it, desc) }
        flushCandidates()
        if (sdpType == "offer") {
            val answer = awaitSdp { conn.createAnswer(it, MediaConstraints()) }
            awaitSet { conn.setLocalDescription(it, answer) }
            onLocalSignal("answer", buildJsonObject {
                put("sdpType", answer.type.canonicalForm())
                put("sdp", answer.description)
            })
        }
    }

    /** 收到远端 candidate：remoteDescription 未就绪则入队（对齐 handleCandidate） */
    fun handleCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        if (closed) return
        val conn = pc ?: return
        val ice = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        if (conn.remoteDescription == null) {
            synchronized(pendingCandidates) { pendingCandidates.add(ice) }
            return
        }
        runCatching { conn.addIceCandidate(ice) }
    }

    private fun flushCandidates() {
        val conn = pc ?: return
        val queued = synchronized(pendingCandidates) {
            val copy = pendingCandidates.toList()
            pendingCandidates.clear()
            copy
        }
        queued.forEach { runCatching { conn.addIceCandidate(it) } }
    }

    /** 静音/取消静音（只动音频轨） */
    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    /** 开/关摄像头（enabled=false 保留 sender 结构，便于恢复） */
    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    /** 释放媒体资源：停采集轨、释放 source、关闭 pc（对齐 close） */
    fun close() {
        if (closed) return
        closed = true
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        localStreamRef = null
        runCatching { pc?.close() }
        pc = null
    }
}

/**
 * createOffer/createAnswer 的挂起封装。
 * 注意：native 回调在内部线程，绝不能抛异常（会直接崩 app），失败一律 resumeWithException。
 */
private suspend fun awaitSdp(block: (org.webrtc.SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { cont ->
        block(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(IllegalStateException(error ?: "create SDP 失败"))
            }
            override fun onSetFailure(error: String?) {
                cont.resumeWithException(IllegalStateException(error ?: "set SDP 失败"))
            }
        })
    }

/** setLocal/RemoteDescription 的挂起封装，等待 onSetSuccess 保证时序 */
private suspend fun awaitSet(block: (org.webrtc.SdpObserver) -> Unit): Unit =
    suspendCancellableCoroutine { cont ->
        block(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(IllegalStateException(error ?: "set SDP 失败"))
            }
            override fun onSetFailure(error: String?) {
                cont.resumeWithException(IllegalStateException(error ?: "set SDP 失败"))
            }
        })
    }
