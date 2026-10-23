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
 * 解析一行 SDP candidate：`candidate:<foundation> <family> <proto> <priority> <ip> <port> typ <type> ...`
 *
 * 只取诊断要用的四件事，解析不出来就留空串，绝不因为诊断把主链路带崩。
 * `mdns` 标记这个地址是 Chrome/Edge 的 mDNS 混淆名（`xxxx.local`）而不是明文 IP：
 * 局域网通话打不通时，这是「对端解析不了本端候选」的关键线索（USB 网络共享这类点对点链路上
 * mDNS 组播常常过不去）。
 */
function parseCandidate(line) {
  const parts = String(line || '').trim().split(/\s+/)
  const address = parts[4] || ''
  const typIndex = parts.indexOf('typ')
  return {
    address,
    proto: (parts[2] || '').toLowerCase(),
    type: typIndex >= 0 ? parts[typIndex + 1] : '',
    mdns: /\.local$/i.test(address)
  }
}

/**
 * ICE 诊断采集器。
 *
 * 过去「P2P 连接失败」就是浮层上一句话，可局域网通话打不通的原因至少有三类，
 * 现场特征完全不同却看不出来：① 电脑防火墙拦了浏览器的入站 UDP（电脑打给手机能通、
 * 手机打给电脑不通就是它）；② 两端根本不在同一网段（比如一边路由器 Wi-Fi、一边手机热点）；
 * ③ mDNS `.local` 候选解析不了。三者的解法互不相干，靠猜只能一遍遍让用户重拨。
 * 所以这里把两端各自收集到的候选、收到的对端候选、connectionState 轨迹攒起来，
 * 失败时拼成一句话显示在浮层上（手机也能截图），成功时只进 console。
 */
function createIceDiag() {
  return {
    local: [], // 本端收集到的候选
    remote: [], // 收到的对端候选
    queued: 0, // 对端 SDP 未就绪时先入队的候选数
    dropped: 0, // addIceCandidate 抛错的候选数
    states: [] // connectionState 变迁轨迹（去重连续相同值）
  }
}

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
  const diag = createIceDiag()
  let closed = false

  function ensurePc() {
    if (pc || closed) {
      return pc
    }
    pc = new RTCPeerConnection({ iceServers })

    pc.onicecandidate = (event) => {
      // candidate 为 null 表示 ICE 收集结束，无需上报
      if (event.candidate) {
        diag.local.push(parseCandidate(event.candidate.candidate))
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
      const state = pc ? pc.connectionState : 'closed'
      if (diag.states[diag.states.length - 1] !== state) {
        diag.states.push(state)
      }
      onConnectionState(state)
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
      diag.remote.push(parseCandidate(candidate && candidate.candidate))
      diag.queued += 1
      return
    }
    diag.remote.push(parseCandidate(candidate && candidate.candidate))
    try {
      await conn.addIceCandidate(new RTCIceCandidate(candidate))
    } catch (error) {
      diag.dropped += 1
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

  /** 把候选列表压成 `host×2/srflx×1` 这种计数形式，一行能看完 */
  function countTypes(list) {
    const counts = {}
    list.forEach((item) => {
      const key = item.type || '?'
      counts[key] = (counts[key] || 0) + 1
    })
    return Object.keys(counts)
      .map((key) => `${key}×${counts[key]}`)
      .join('/')
  }

  /**
   * 一行可读诊断，给通话浮层用（手机截图就能看懂）。
   *
   * 「对端候选 0」= 信令没把 candidate 送过来（信令/版本问题）；
   * 「对端候选 N 但从未 connected」= 收得到候选、发不过去（防火墙 / 不同网段）；
   * 「.local」出现 = 候选是 mDNS 混淆名，得靠组播解析，点对点链路上常常解不开。
   */
  function iceSummary() {
    const mdnsRemote = diag.remote.filter((item) => item.mdns).length
    const parts = [
      `本端候选 ${diag.local.length}（${countTypes(diag.local) || '无'}）`,
      `对端候选 ${diag.remote.length}（${countTypes(diag.remote) || '无'}${mdnsRemote ? `，${mdnsRemote} 个 .local` : ''}）`
    ]
    if (diag.dropped) {
      parts.push(`丢弃 ${diag.dropped}`)
    }
    if (diag.states.length) {
      parts.push(`状态 ${diag.states.join('→')}`)
    }
    return parts.join(' · ')
  }

  /** 完整现场（含每个候选的地址与端口），只进 console，不进界面 */
  function iceDetail() {
    return {
      iceServers: iceServers.length,
      local: diag.local,
      remote: diag.remote,
      queued: diag.queued,
      dropped: diag.dropped,
      states: diag.states.slice()
    }
  }

  /**
   * 从 getStats 里取最终选通（selected / nominated）的候选对，用来区分
   * 「一个候选对都没走通」与「走通了但媒体传不动」。拿不到就回一句人话，不抛错。
   */
  async function selectedPairText() {
    if (!pc) {
      return '无 PeerConnection'
    }
    try {
      const stats = await pc.getStats()
      const byId = new Map()
      let selected = null
      stats.forEach((entry) => {
        byId.set(entry.id, entry)
        if (entry.type === 'candidate-pair' && (entry.selected || entry.nominated)) {
          selected = entry
        }
      })
      if (!selected) {
        return '未选通任何候选对'
      }
      const local = byId.get(selected.localCandidateId) || {}
      const remote = byId.get(selected.remoteCandidateId) || {}
      const ip = (c) => c.address || c.ip || '?'
      return `选通 ${local.candidateType || '?'}→${remote.candidateType || '?'}（${ip(local)}:${local.port || '?'} → ${ip(remote)}:${remote.port || '?'}）`
    } catch (error) {
      console.warn('[webrtc] getStats 失败', error)
      return ''
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
    /** 一行可读 ICE 诊断（浮层用） */
    iceSummary,
    /** ICE 诊断完整结构体（console 用） */
    iceDetail,
    /** 异步：最终选通的候选对描述 */
    selectedPairText,
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
