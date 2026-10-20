/**
 * 一对一语音视频通话的 WebRTC 引擎（仅封装 RTCPeerConnection 生命周期，不含业务状态）。
 *
 * 完全用浏览器 / Electron(Chromium) 原生能力：getUserMedia 采集 + RTCPeerConnection 点对点传输，
 * 不引入任何外部 RTC SDK。信令（offer/answer/ice）由调用方（stores/call.js）经已认证的
 * WebSocket 长连接收发，本模块只负责：拿到本地流、按 ICE 清单建 pc、序列化 SDP 与 candidate、
 * 维护 trickle-ICE 的到达顺序，以及静音 / 关摄像头 / 屏幕共享 / 挂断等轨操作。
 *
 * 设计取向：
 * - 不在此处发信令。onLocalSignal 回调把 { action, data } 抛给上层，由上层决定走 ws 还是别的，
 *   引擎保持与传输方式解耦，将来换传输层不用动这里。
 * - ICE candidate 可能在远端 SDP 落位前就到（trickle 竞态），先入队，setRemote 后统一冲刷。
 * - 内网直连优先：iceServers 为空时只用 host candidate，媒体不经服务器、不耗流量。
 */

/** 采集约束：语音恒开并做回声/降噪抑制；视频按 mediaType 决定是否请求 */
function mediaConstraints(mediaType) {
  const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
  if (mediaType === 'video') {
    return { audio, video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } } }
  }
  return { audio, video: false }
}

/**
 * 本环境能不能拿到麦/摄像头。
 *
 * 浏览器只在安全上下文（https 或 localhost/127.0.0.1）暴露 navigator.mediaDevices：
 * 手机用 http://局域网IP 访问时它是 undefined，不预检直接调 getUserMedia 会撞一个天书一样的
 * TypeError（navigator.mediaDevices 不存在），用户只看到「接通后就关闭」。
 *
 * 1:1 通话与群通话都先问这一句，所以放在引擎模块里而不是某一个 store 里。
 */
export function hasMediaApi() {
  return !!(typeof navigator !== 'undefined' && navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
}

/** 拿不到媒体设备时的统一文案，两个通话场景共用一份，免得改一处漏一处 */
export const INSECURE_HINT =
  '当前访问地址不是 HTTPS/localhost，浏览器禁止使用麦克风/摄像头：手机请用 https://<电脑IP>:5173 访问（npm run gen:cert 生成证书）'

/**
 * 创建一个通话引擎实例。一次通话对应一个实例，挂断即弃。
 *
 * @param {object} options
 * @param {Array} options.iceServers RTCConfiguration.iceServers（来自 /api/call/config）
 * @param {string} options.mediaType 'audio' | 'video'
 * @param {(action: string, data: object) => void} options.onLocalSignal 本地产生的信令：'offer'|'answer'|'ice'
 * @param {(stream: MediaStream) => void} [options.onRemoteStream] 远端流就绪（挂到 <video>.srcObject）
 * @param {(stream: MediaStream) => void} [options.onLocalStream] 本地流就绪（预览小窗 + 录制取流）
 * @param {(state: string) => void} [options.onConnectionState] RTCPeerConnection 连接状态变化
 * @param {MediaStream} [options.localStream] 外部已有的本地流。**群通话（mesh）必传**：
 *   四人通话要开三路 RTCPeerConnection，若不共享，每一路都会各自 getUserMedia 一次——
 *   Windows 上同一摄像头设备被重复打开并不安全（常见表现为第二路黑屏或整个设备被占用报错），
 *   而且三路采集意味着三份编码开销。传了外部流时本实例不采集、
 *   {@code close()} 也**不停**这些轨（谁采集谁销毁），其余 N-1 路不受影响。
 *   附带一个语义：{@code setMuted}/{@code setCameraEnabled} 动的是共享轨，
 *   于是一按静音就对房间里所有人同时静音——这正是群通话想要的效果，不是副作用。
 * @returns {object} 引擎句柄，见下方方法
 */
export function createCallPeer(options = {}) {
  const {
    iceServers = [],
    mediaType = 'audio',
    onLocalSignal = () => {},
    onRemoteStream = () => {},
    onLocalStream = () => {},
    onConnectionState = () => {}
  } = options

  let pc = null
  // 共享流由调用方注入，所有权也在调用方；自己采集的才归自己停
  let localStream = options.localStream || null
  const ownsStream = !options.localStream
  let screenStream = null
  // 远端 candidate 早于 remoteDescription 到达时暂存，setRemote 后冲刷
  const pendingCandidates = []
  let closed = false

  function ensurePc() {
    if (pc || closed) {
      return pc
    }
    pc = new RTCPeerConnection({ iceServers })

    pc.onicecandidate = (event) => {
      // candidate 为 null 表示 ICE 收集结束，无需上报
      if (event.candidate) {
        onLocalSignal('ice', { candidate: event.candidate.toJSON ? event.candidate.toJSON() : event.candidate })
      }
    }
    pc.ontrack = (event) => {
      const stream = event.streams && event.streams[0]
      if (stream) {
        onRemoteStream(stream)
      }
    }
    pc.onconnectionstatechange = () => {
      onConnectionState(pc ? pc.connectionState : 'closed')
    }
    return pc
  }

  /** 采集本地音视频并挂到 pc；采集失败（无权限/无设备）时抛错，由上层提示。已有共享流时只挂不采 */
  async function initMedia() {
    if (closed) {
      throw new Error('通话已结束')
    }
    if (!localStream) {
      localStream = await navigator.mediaDevices.getUserMedia(mediaConstraints(mediaType))
    }
    onLocalStream(localStream)
    const conn = ensurePc()
    localStream.getTracks().forEach((track) => conn.addTrack(track, localStream))
    return localStream
  }

  /** 主叫在收到被叫 accept 后调用：生成 offer 并经信令发出 */
  async function createOffer() {
    const conn = ensurePc()
    const offer = await conn.createOffer()
    await conn.setLocalDescription(offer)
    onLocalSignal('offer', { sdpType: conn.localDescription.type, sdp: conn.localDescription.sdp })
  }

  /**
   * 收到远端 offer 或 answer：落 remoteDescription 并冲刷排队 candidate；
   * 被叫侧在 offer 后顺手生成 answer 回投。
   * @returns {Promise<void>}
   */
  async function handleDescription(sdpType, sdp) {
    const conn = ensurePc()
    await conn.setRemoteDescription(new RTCSessionDescription({ type: sdpType, sdp }))
    flushCandidates()
    if (sdpType === 'offer') {
      const answer = await conn.createAnswer()
      await conn.setLocalDescription(answer)
      onLocalSignal('answer', { sdpType: conn.localDescription.type, sdp: conn.localDescription.sdp })
    }
  }

  /** 收到远端 candidate：remoteDescription 未就绪则入队，否则直接添加 */
  async function handleCandidate(candidate) {
    const conn = ensurePc()
    if (!conn.remoteDescription) {
      pendingCandidates.push(candidate)
      return
    }
    try {
      await conn.addIceCandidate(new RTCIceCandidate(candidate))
    } catch (error) {
      console.warn('[webrtc] addIceCandidate 失败', error)
    }
  }

  function flushCandidates() {
    const conn = pc
    if (!conn) {
      return
    }
    while (pendingCandidates.length) {
      const candidate = pendingCandidates.shift()
      conn.addIceCandidate(new RTCIceCandidate(candidate)).catch((error) => console.warn('[webrtc] 冲刷 candidate 失败', error))
    }
  }

  /** 静音 / 取消静音（只动音频轨，不影响传输本身） */
  function setMuted(muted) {
    if (!localStream) {
      return
    }
    localStream.getAudioTracks().forEach((track) => {
      track.enabled = !muted
    })
  }

  /** 开 / 关摄像头（关停用 enabled=false，保持 sender 结构不变，便于恢复） */
  function setCameraEnabled(enabled) {
    if (!localStream) {
      return
    }
    localStream.getVideoTracks().forEach((track) => {
      track.enabled = enabled
    })
  }

  /**
   * 开关屏幕共享：开启时用 getDisplayMedia 的视频轨替换当前视频 sender 的摄像头轨；
   * 关闭时换回原摄像头轨。纯语音通话（无视频 sender）时不支持，返回 false。
   * @returns {Promise<boolean>} 是否切换成功
   */
  async function setScreenShare(enable) {
    const conn = ensurePc()
    const videoSender = conn.getSenders().find((s) => s.track && s.track.kind === 'video')
    if (!videoSender) {
      return false
    }
    if (enable) {
      screenStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
      const screenTrack = screenStream.getVideoTracks()[0]
      // 用户在系统层停止共享时，同步撤掉本端的共享状态
      screenTrack.onended = () => setScreenShare(false)
      await videoSender.replaceTrack(screenTrack)
    } else {
      const cameraTrack = localStream ? localStream.getVideoTracks()[0] : null
      await videoSender.replaceTrack(cameraTrack || null)
      if (screenStream) {
        screenStream.getTracks().forEach((t) => t.stop())
        screenStream = null
      }
    }
    return true
  }

  /** 释放媒体资源：停本地轨（仅自己采集的那些）、停共享轨、关闭 pc */
  function close() {
    if (closed) {
      return
    }
    closed = true
    if (localStream) {
      // 共享流的轨属于群通话本体，剩下 N-2 路还在用，误停就是「一个人挂断全房黑屏」
      if (ownsStream) {
        localStream.getTracks().forEach((track) => track.stop())
      }
      localStream = null
    }
    if (screenStream) {
      screenStream.getTracks().forEach((track) => track.stop())
      screenStream = null
    }
    if (pc) {
      try {
        pc.onicecandidate = null
        pc.ontrack = null
        pc.onconnectionstatechange = null
        pc.close()
      } catch {
        /* 关闭异常不影响资源已释放的事实 */
      }
      pc = null
    }
  }

  return {
    initMedia,
    createOffer,
    handleDescription,
    handleCandidate,
    setMuted,
    setCameraEnabled,
    setScreenShare,
    close,
    /** 本地媒体流，供界面预览与录制取流 */
    get localStream() {
      return localStream
    },
    get peerConnection() {
      return pc
    },
    get ended() {
      return closed
    }
  }
}
