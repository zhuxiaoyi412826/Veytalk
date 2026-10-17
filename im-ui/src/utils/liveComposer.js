/**
 * 主播端「连麦合成推流」：把主播 + 若干嘉宾的 WebRTC 流在浏览器里合成成一路直播流。
 *
 * 完全用浏览器 / Electron(Chromium) 原生能力，零新增依赖：
 * - 视频：离屏 Canvas 按布局逐帧绘制各路 <video>（requestVideoFrameCallback 驱动，比 setInterval 省且合帧）；
 * - 音频：WebAudio 把主播麦与嘉宾音混到一路（MediaStreamAudioDestinationNode）；
 * - 采集：canvas.captureStream(fps) 取合成画面，拼上混音轨组成一个 MediaStream；
 * - 录制：MediaRecorder 固定 WebM（vp9/opus，回落 vp8），按 timeslice≈1000ms 吐分片；
 * - 上传：首块（含 EBML/CodecPrivate）当初始化段，其余当媒体分片，逐个 fetch PUT 到同源媒体端点，
 *   每传完一片刷新一份轻量清单 manifest.json，供观众端 MSE 播放器滚动追加。
 *
 * 为什么是 WebM 不是 MP4：MediaRecorder 录 MP4 不按 timeslice 吐片（整段到 stop 才落一条），
 * 直播切片场景根本不可用（项目已知坑）。WebM 首块自带初始化信息，MSE 侧把首块当 init、后续块
 * 直接 appendBuffer 即可续播，无需 fMP4 的 EXT-X-MAP 那套。
 *
 * 与 ffmpeg→HLS 链路的关系：非连麦直播仍走 Electron 原生 ffmpeg，不经这里；只有连麦（有人在麦）
 * 时才启用本合成器，观众端相应从 hls.js 切到 utils/webmPlayer.js。两条链路互不干扰。
 */
import { livePlayBase } from './env'

/** 录制的候选容器/编码，从最优到兼容回落；MSE 侧 codecs 需与之匹配 */
const RECORDER_TYPES = [
  'video/webm;codecs=vp9,opus',
  'video/webm;codecs=vp8,opus',
  'video/webm'
]

/** 挑一个本浏览器真正支持的 MediaRecorder mimeType，都不支持返回空串 */
export function pickWebmMimeType() {
  if (typeof MediaRecorder === 'undefined') {
    return ''
  }
  for (const type of RECORDER_TYPES) {
    try {
      if (MediaRecorder.isTypeSupported(type)) {
        return type
      }
    } catch {
      /* 某些环境 isTypeSupported 抛错，视为不支持继续试 */
    }
  }
  return ''
}

/** 连麦合成推流是否可用（缺 MediaRecorder / captureStream 就不必尝试） */
export function composerSupported() {
  return (
    typeof MediaRecorder !== 'undefined' &&
    !!pickWebmMimeType() &&
    typeof HTMLCanvasElement !== 'undefined' &&
    typeof HTMLCanvasElement.prototype.captureStream === 'function'
  )
}

/**
 * 由房间播放地址推出连麦合成流的媒体目录基址（观众读、主播写都用它）：
 * playUrl 形如 `/hls/{roomId}/{streamKey}/index.m3u8?expire=&sign=`，去掉尾段文件名与查询即得目录。
 * @param {string} playUrl 后端下发的播放地址（相对或绝对）
 * @returns {string} 绝对或同源的目录基址（不含结尾斜杠），如 `http://127.0.0.1:8088/hls/1/abc`；无 playUrl 返回空串
 */
export function webmBaseFromPlayUrl(playUrl) {
  if (!playUrl) {
    return ''
  }
  // 去掉签名查询（? 之后），再去掉最后一个路径段（index.m3u8）
  const noQuery = String(playUrl).split('?')[0]
  const dir = noQuery.slice(0, noQuery.lastIndexOf('/'))
  // 相对路径补媒体 host：Web 同源留空串，Electron 用本机流媒体绝对地址（与 setupPlayer 同一策略）
  const src = /^https?:\/\//i.test(dir) ? dir : livePlayBase() + dir
  return src.replace(/\/+$/, '')
}

/**
 * 创建一个合成推流器。一次连麦直播对应一个实例，下麦/关播即 stop 弃用。
 *
 * @param {object} options
 * @param {string} options.mediaBase webmBaseFromPlayUrl 得到的目录基址
 * @param {number} [options.width=1280] 合成画布宽
 * @param {number} [options.height=720] 合成画布高
 * @param {number} [options.fps=15] captureStream 帧率
 * @param {(state: string, info?: object) => void} [options.onState] 'starting'|'recording'|'stopped'|'error'
 * @param {(msg: string) => void} [options.onWarn] 分片上传失败等非致命告警
 * @returns {object} 句柄：addSource / removeSource / start / stop / get recording
 */
export function createLiveComposer(options = {}) {
  const {
    mediaBase,
    width = 1280,
    height = 720,
    fps = 15,
    onState = () => {},
    onWarn = () => {}
  } = options

  if (!mediaBase) {
    throw new Error('liveComposer 缺少 mediaBase')
  }

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')

  /** id → { stream, video, kind, label }，kind='anchor'|'guest'；video 是内部隐藏 <video>，供逐帧取像 */
  const sources = new Map()

  let audioCtx = null
  let destNode = null
  /** id → MediaStreamAudioSourceNode→gain，removeSource 时断开 */
  const audioNodes = new Map()

  let recorder = null
  let rafId = 0
  let segSeq = 0
  let initSent = false
  let running = false
  let stopping = false

  /* --------------------------- 上传泵（串行，保证顺序） --------------------------- */
  const queue = [] // { name, blob } 顺序即 PUT 顺序，manifest 由调用方在段后追加
  let pumping = false

  function enqueue(name, blob) {
    queue.push({ name, blob })
    pump()
  }

  async function pump() {
    if (pumping) {
      return
    }
    pumping = true
    try {
      while (queue.length) {
        const item = queue.shift()
        const ok = await putBlob(item.name, item.blob)
        if (!ok) {
          onWarn(`合成分片上传失败：${item.name}`)
        }
      }
    } finally {
      pumping = false
    }
  }

  /** PUT 一个 blob 到 {mediaBase}/webm/{name}，失败重试一次（与 electron/live.js putFile 同思路） */
  async function putBlob(name, blob) {
    const url = `${mediaBase}/webm/${name}`
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const res = await fetch(url, {
          method: 'PUT',
          headers: { 'Content-Type': blob.type || 'video/webm' },
          body: blob
        })
        if (res.ok || res.status === 201 || res.status === 204) {
          return true
        }
      } catch {
        /* 网络抖动，重试 */
      }
    }
    return false
  }

  /** 刷新清单：观众轮询它拿 init 与最新分片序号 */
  function writeManifest() {
    const manifest = {
      init: 'init.webm',
      count: segSeq,
      mimeType: recorder ? recorder.mimeType : pickWebmMimeType(),
      updated: Date.now()
    }
    enqueue('manifest.json', new Blob([JSON.stringify(manifest)], { type: 'application/json' }))
  }

  /* --------------------------- 视频源与绘制 --------------------------- */

  /** 为一路流建隐藏 <video> 并接入音频图，开始参与合成。label 是画在分格左下角的名牌（主播/嘉宾昵称） */
  function addSource(id, stream, kind = 'guest', label = '') {
    if (sources.has(id)) {
      removeSource(id)
    }
    const video = document.createElement('video')
    // 这里必须静音：本元素只负责给 canvas 取帧，扬声器的声音由界面层的分屏 <video> 负责，
    // 两边都出声会形成双倍音量与回声
    video.muted = true
    video.autoplay = true
    video.playsInline = true
    video.srcObject = stream
    video.play().catch(() => {})
    sources.set(id, { stream, video, kind, label })
    attachAudio(stream, id, kind)
    return video
  }

  function removeSource(id) {
    const src = sources.get(id)
    if (!src) {
      return
    }
    detachAudio(id)
    src.video.srcObject = null
    sources.delete(id)
  }

  /** 改名牌文案：嘉宾昵称是拉到在麦列表后才异步补齐的，画布上的字要跟着刷新 */
  function setLabel(id, label) {
    const src = sources.get(id)
    if (src) {
      src.label = label
    }
  }

  function attachAudio(stream, id, kind = 'guest') {
    // 没有音频轨的源（比如纯屏幕共享）只参与画面，跳过即可，不算异常
    if (!audioCtx || !stream.getAudioTracks().length) {
      return
    }
    try {
      const sourceNode = audioCtx.createMediaStreamSource(stream)
      const gain = audioCtx.createGain()
      gain.gain.value = kind === 'anchor' ? 1 : 0.9
      sourceNode.connect(gain)
      // destNode 是「录制用的那一路」，只连这里就意味着：合成流里有声，但主播本地听不到嘉宾。
      // 主播的监听走界面层未静音的 <video>（浏览器原生 AEC 更好，不必在这里再连 destination）
      gain.connect(destNode)
      audioNodes.set(id, { sourceNode, gain })
    } catch {
      /* 音频接入失败不影响画面 */
    }
  }

  function detachAudio(id) {
    const node = audioNodes.get(id)
    if (node) {
      try {
        node.sourceNode.disconnect()
        node.gain.disconnect()
      } catch {
        /* 忽略 */
      }
      audioNodes.delete(id)
    }
  }

  /**
   * 逐帧绘制。无人上麦时主播铺满整幅（与不连麦时观感一致）；有人上麦即改为抖音式左右分屏：
   * 左半固定给主播，右半放嘉宾（1 人铺满、2 人上下、3~4 人 2×2）。
   * 观众看到的画面布局就是这个 canvas，界面层的本地分屏舞台与之同构，以免「主播自看到的和观众看到的不一样」。
   */
  function drawFrame() {
    if (!running) {
      return
    }
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, width, height)

    const anchor = [...sources.values()].find((s) => s.kind === 'anchor')
    const guests = [...sources.values()].filter((s) => s.kind === 'guest')

    if (!guests.length) {
      if (anchor) {
        drawCover(anchor.video, 0, 0, width, height)
        drawLabel(anchor.label || '主播', 14, height - 16)
      } else {
        drawPlaceholder(0, 0, width, height, '等待主播画面')
      }
    } else {
      drawSplit(anchor, guests)
    }

    // requestVideoFrameCallback 优先（与主播视频解码同帧，省 CPU 且不重绘撕裂帧），不支持回落 rAF
    const anchorVideo = anchor && anchor.video
    if (anchorVideo && typeof anchorVideo.requestVideoFrameCallback === 'function') {
      rafId = anchorVideo.requestVideoFrameCallback(drawFrame)
    } else {
      rafId = requestAnimationFrame(drawFrame)
    }
  }

  /** 左右分屏：主播居左、嘉宾居右（多人右侧网格堆叠） */
  function drawSplit(anchor, guests) {
    const half = Math.round(width / 2)
    const gap = 4
    if (anchor) {
      drawContain(anchor.video, 0, 0, half - gap, height)
      drawLabel(anchor.label || '主播', 14, height - 16)
    } else {
      drawPlaceholder(0, 0, half - gap, height, '等待主播画面')
    }

    const n = guests.length
    const cols = n > 2 ? 2 : 1
    const rows = Math.ceil(n / cols)
    const cellW = (width - half - gap * (cols + 1)) / cols
    const cellH = (height - gap * (rows + 1)) / rows
    guests.forEach((s, i) => {
      const col = i % cols
      const row = Math.floor(i / cols)
      const x = half + gap + col * (cellW + gap)
      const y = gap + row * (cellH + gap)
      ctx.fillStyle = '#111'
      ctx.fillRect(x, y, cellW, cellH)
      drawContain(s.video, x, y, cellW, cellH)
      ctx.strokeStyle = 'rgba(255,255,255,0.25)'
      ctx.strokeRect(x + 0.5, y + 0.5, cellW - 1, cellH - 1)
      drawLabel(s.label || '嘉宾', x + 10, y + cellH - 14)
    })

    // 中缝分隔线：分屏感更明确，也遮住两格抗锯齿边缘的拼缝
    ctx.fillStyle = 'rgba(255,255,255,0.18)'
    ctx.fillRect(half - gap, 0, gap, height)
  }

  /** 目标矩形内铺满提示文字（流未就绪时不给观众看纯黑块） */
  function drawPlaceholder(x, y, w, h, text) {
    ctx.save()
    ctx.fillStyle = '#111'
    ctx.fillRect(x, y, w, h)
    ctx.fillStyle = 'rgba(255,255,255,0.55)'
    ctx.font = `${Math.round(Math.min(w, h) * 0.07)}px system-ui, "Microsoft YaHei", sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(text, x + w / 2, y + h / 2)
    ctx.restore()
  }

  /** 分格左下角名牌：胶囊底 + 白字，字号随画面高自适应 */
  function drawLabel(text, x, y) {
    if (!text) {
      return
    }
    ctx.save()
    const fontSize = Math.max(13, Math.round(height * 0.032))
    ctx.font = `${fontSize}px system-ui, "Microsoft YaHei", sans-serif`
    ctx.textBaseline = 'middle'
    const pillW = ctx.measureText(text).width + 18
    const pillH = fontSize + 12
    roundRectPath(x - 9, y - pillH / 2, pillW, pillH, pillH / 2)
    ctx.fillStyle = 'rgba(0,0,0,0.45)'
    ctx.fill()
    ctx.fillStyle = '#fff'
    ctx.fillText(text, x, y)
    ctx.restore()
  }

  /** 圆角矩形路径（不用 ctx.roundRect：旧版 Chromium/Electron 无此 API，会整帧抛错） */
  function roundRectPath(x, y, w, h, r) {
    const rad = Math.min(r, w / 2, h / 2)
    ctx.beginPath()
    ctx.moveTo(x + rad, y)
    ctx.lineTo(x + w - rad, y)
    ctx.arcTo(x + w, y, x + w, y + rad, rad)
    ctx.lineTo(x + w, y + h - rad)
    ctx.arcTo(x + w, y + h, x + w - rad, y + h, rad)
    ctx.lineTo(x + rad, y + h)
    ctx.arcTo(x, y + h, x, y + h - rad, rad)
    ctx.lineTo(x, y + rad)
    ctx.arcTo(x, y, x + rad, y, rad)
    ctx.closePath()
  }

  /** 按 cover 铺放视频帧到目标矩形（居中裁边）：单人满屏时用，不致于拉变比例 */
  function drawCover(video, x, y, w, h) {
    if (!video || video.readyState < 2 || !video.videoWidth) {
      return
    }
    const vr = video.videoWidth / video.videoHeight
    const tr = w / h
    let sw, sh, sx, sy
    if (vr > tr) {
      sh = video.videoHeight
      sw = sh * tr
      sx = (video.videoWidth - sw) / 2
      sy = 0
    } else {
      sw = video.videoWidth
      sh = sw / tr
      sx = 0
      sy = (video.videoHeight - sh) / 2
    }
    try {
      ctx.drawImage(video, sx, sy, sw, sh, x, y, w, h)
    } catch {
      /* 帧尚未就绪，跳过本帧 */
    }
  }

  /**
   * 按 contain 铺放（完整可见、居中留黑边）：分屏专用。
   * 摄像头常见 16:9，分屏每格是竖长条，若还用 cover 会只裁到画面中间一条，人脸容易被切掉。
   */
  function drawContain(video, x, y, w, h) {
    if (!video || video.readyState < 2 || !video.videoWidth) {
      return
    }
    const vr = video.videoWidth / video.videoHeight
    const tr = w / h
    let dw, dh, dx, dy
    if (vr > tr) {
      dw = w
      dh = w / vr
      dx = x
      dy = y + (h - dh) / 2
    } else {
      dh = h
      dw = h * vr
      dy = y
      dx = x + (w - dw) / 2
    }
    try {
      ctx.drawImage(video, dx, dy, dw, dh)
    } catch {
      /* 帧尚未就绪，跳过本帧 */
    }
  }

  /* --------------------------- 生命周期 --------------------------- */

  async function start() {
    if (running) {
      return
    }
    const mimeType = pickWebmMimeType()
    if (!mimeType) {
      onState('error', { error: '当前浏览器不支持 WebM 录制，无法连麦合成推流' })
      return
    }
    // 音频图
    audioCtx = new (window.AudioContext || window.webkitAudioContext)()
    // 关键：AudioContext 可能以 suspended 创建（非用户手势里误新建、或浏览器自动播放策略），
    // suspended 状态下图不跑→ destNode 的音轨持续输出静音→ 录出来的 WebM 有音轨但全无声，
    // 表现为「观众看连麦有画面没声音」。resume 是异步的，不 await 则可能连前几帧都依旧静音
    if (audioCtx.state === 'suspended') {
      try {
        await audioCtx.resume()
      } catch {
        /* 无用户手势时被拒：不阻断画面，声音等下一次 resume 机会 */
      }
    }
    destNode = audioCtx.createMediaStreamDestination()
    // 已存在的源补接音频
    sources.forEach((s, id) => attachAudio(s.stream, id, s.kind))

    const stream = canvas.captureStream(fps)
    destNode.stream.getAudioTracks().forEach((t) => stream.addTrack(t))

    segSeq = 0
    initSent = false
    recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 2500000 })
    recorder.ondataavailable = (evt) => {
      if (!evt.data || evt.data.size === 0) {
        return
      }
      if (!initSent) {
        // 首块含 EBML header + CodecPrivate，作为初始化段
        initSent = true
        enqueue('init.webm', evt.data)
      } else {
        segSeq += 1
        enqueue(`seg${segSeq}.webm`, evt.data)
        writeManifest()
      }
    }
    recorder.onerror = (evt) => {
      onState('error', { error: (evt && evt.error && evt.error.name) || 'MediaRecorder 出错' })
    }
    recorder.onstop = () => {
      running = false
      onState('stopped')
    }

    running = true
    stopping = false
    onState('starting')
    // timeslice≈1000ms：每秒吐一片，与 HLS 1s 片对齐，延迟可控
    recorder.start(1000)
    drawFrame()
    // 立刻写一份空清单，观众据此判定「合成流已开始」并等 init/seg
    writeManifest()
    onState('recording')
  }

  function stop() {
    if (!running && !stopping) {
      return
    }
    stopping = true
    running = false
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    if (recorder && recorder.state !== 'inactive') {
      try {
        recorder.stop() // 触发最后一块 dataavailable + onstop
      } catch {
        /* 已停止 */
      }
    }
    recorder = null
    // 收掉音频图
    audioNodes.forEach((node) => {
      try {
        node.sourceNode.disconnect()
        node.gain.disconnect()
      } catch {
        /* 忽略 */
      }
    })
    audioNodes.clear()
    if (audioCtx) {
      audioCtx.close().catch(() => {})
      audioCtx = null
      destNode = null
    }
    // 关播收尾：把清单 count 停在最终值，观众看到不再增长即知下麦
    writeManifest()
  }

  return {
    addSource,
    removeSource,
    setLabel,
    start,
    stop,
    get recording() {
      return running
    },
    get segmentCount() {
      return segSeq
    }
  }
}
