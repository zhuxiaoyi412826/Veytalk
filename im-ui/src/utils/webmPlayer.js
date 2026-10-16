/**
 * 观众端「连麦合成流」播放器：用 MSE（MediaSource + SourceBuffer）滚动追加主播端 PUT 上来的 WebM 分片。
 *
 * 与 hls.js 的分工：非连麦直播仍是 ffmpeg→HLS→hls.js（不变）；只有主播开连麦、把「主播+嘉宾」
 * 合成推成 WebM 分片时，观众才切到这个播放器（utils/liveComposer.js 的对端）。
 *
 * 数据来源是 liveComposer 每传完一片刷新的轻量清单 manifest.json：
 *   { init: 'init.webm', count: <最新分片序号>, mimeType: 'video/webm;codecs=vp9,opus', updated }
 * 播放流程：轮询清单 → 首次拿到 init 段建 SourceBuffer 并 append → 逐个 append 新分片 segN.webm。
 * WebM 首块含 EBML header + CodecPrivate，MSE 视其为初始化段，后续块直接续 append 即可解码（无需 fMP4 的 EXT-X-MAP）。
 *
 * 三个必须处理的坑：
 * - appendBuffer 串行：SourceBuffer.updating 期间不能再 append，否则抛错。用队列 + updateend 驱动逐条喂。
 * - 直播延迟：落后直播边缘过多会把画面卡在过去。检测 currentTime 与 buffered.end 的差，超阈值就跳到近边缘。
 * - 内存：长连麦分片无限增长，滚动 removeRange 丢弃最旧的缓冲，只保留一段滑动窗口。
 */
import { webmBaseFromPlayUrl } from './liveComposer'

/** SourceBuffer 候选 codec，从最优到兼容；实际以清单 mimeType 为准，取其与本浏览器支持的交集 */
const FALLBACK_CODECS = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm']

/** 落后直播边缘超过这个秒数就追帧（合成流直播，几秒的滞后已影响互动观感） */
const MAX_LAG_SECONDS = 3.5
/** 缓冲保留窗口：超过则丢弃最旧区间，控内存同时留够抗抖动的余量 */
const KEEP_BUFFER_SECONDS = 20
/** 清单轮询间隔 */
const POLL_MS = 1500

/** 连麦合成流播放是否可用（缺 MediaSource 就没法 MSE） */
export function webmPlayerSupported() {
  return typeof MediaSource !== 'undefined' && typeof MediaSource.isTypeSupported === 'function'
}

/** 挑一个本浏览器支持的 webm codec；优先清单给的 mimeType */
function pickCodec(preferred) {
  const list = preferred ? [preferred, ...FALLBACK_CODECS] : FALLBACK_CODECS
  for (const codec of list) {
    try {
      if (MediaSource.isTypeSupported(codec)) {
        return codec
      }
    } catch {
      /* 某些环境 isTypeSupported 抛错，跳过 */
    }
  }
  return ''
}

/**
 * 创建一个连麦合成流播放器。一次连麦观看对应一个实例，退房/关播即 stop 销毁。
 *
 * @param {object} options
 * @param {HTMLVideoElement} options.video 绑定 srcObject 的 <video>
 * @param {string} options.playUrl 后端下发的播放地址（用于推导 webm 清单与分片的媒体目录）
 * @param {(state: string) => void} [options.onState] 'starting'|'playing'|'waiting'|'stopped'|'error'
 * @param {(msg: string) => void} [options.onError] 不可恢复错误（不支持/建流失败）
 * @returns {{ start: () => void, stop: () => void }}
 */
export function createWebmPlayer(options = {}) {
  const { video, playUrl, onState = () => {}, onError = () => {} } = options

  const mediaBase = webmBaseFromPlayUrl(playUrl)
  let ms = null
  let sb = null
  let objectUrl = ''
  let pollTimer = 0
  let running = false
  let initLoaded = false
  let lastSeg = 0 // 已 append 到的最新分片序号
  const appendQueue = [] // 待 append 的 ArrayBuffer，串行喂
  let appending = false

  async function fetchArrayBuffer(url) {
    const res = await fetch(url, { cache: 'no-store' })
    if (!res.ok) {
      // 404 常见于开播头几秒清单/分片还没 PUT 上来，由轮询自然重试，不当致命错误
      return null
    }
    return res.arrayBuffer()
  }

  /** 把一段 buffer 入队并尝试排空 */
  function enqueueAppend(buffer) {
    appendQueue.push(buffer)
    drainAppend()
  }

  /** SourceBuffer.updating 空闲时逐条 append；忙则等 updateend 回调续排 */
  function drainAppend() {
    if (appending || !sb || sb.updating) {
      return
    }
    const buffer = appendQueue.shift()
    if (!buffer) {
      return
    }
    appending = true
    try {
      sb.appendBuffer(buffer)
    } catch (e) {
      appending = false
      // QuotaExceededError：缓冲太满，清旧数据后重试由下一轮轮询带动
      onState('waiting')
    }
  }

  function onSbUpdateEnd() {
    appending = false
    trimOldBuffer()
    followLiveEdge()
    drainAppend()
  }

  /** 丢弃超出滑动窗口的最旧缓冲，控内存 */
  function trimOldBuffer() {
    if (!sb || sb.updating || !video || !video.buffered.length) {
      return
    }
    const end = video.buffered.end(video.buffered.length - 1)
    const start = video.buffered.start(0)
    if (end - start > KEEP_BUFFER_SECONDS) {
      const until = end - KEEP_BUFFER_SECONDS
      try {
        sb.remove(start, until)
      } catch {
        /* 正在 updating 时 remove 会排队，忽略 */
      }
    }
  }

  /** 落后直播边缘过多就跳到近边缘，保证低延迟 */
  function followLiveEdge() {
    if (!video || !video.buffered.length) {
      return
    }
    const end = video.buffered.end(video.buffered.length - 1)
    if (end - video.currentTime > MAX_LAG_SECONDS) {
      try {
        video.currentTime = Math.max(video.buffered.start(0), end - 1)
      } catch {
        /* seek 越界等，忽略 */
      }
    }
  }

  async function pollOnce() {
    if (!running) {
      return
    }
    const manifest = await fetchJson(`${mediaBase}/webm/manifest.json`)
    if (!manifest) {
      return
    }
    // 首次：建流 + append init 段
    if (!initLoaded) {
      if (manifest.count == null) {
        return
      }
      const initBuf = await fetchArrayBuffer(`${mediaBase}/webm/${manifest.init || 'init.webm'}`)
      if (!initBuf) {
        return // init 还没上来，下一轮再试
      }
      const ok = setupSourceBuffer(manifest.mimeType)
      if (!ok) {
        return
      }
      enqueueAppend(initBuf)
      initLoaded = true
      onState('playing')
    }
    // 追加 init 之后、清单最新 count 之间尚未取的分片
    const target = manifest.count || 0
    while (lastSeg < target) {
      lastSeg += 1
      const segBuf = await fetchArrayBuffer(`${mediaBase}/webm/seg${lastSeg}.webm`)
      if (!segBuf) {
        // 该片尚未 PUT 上来或已过期：计数已前移，避免反复卡在同一个缺失片
        lastSeg -= 1
        break
      }
      enqueueAppend(segBuf)
    }
  }

  async function fetchJson(url) {
    try {
      const res = await fetch(url, { cache: 'no-store' })
      if (!res.ok) {
        return null
      }
      return await res.json()
    } catch {
      return null
    }
  }

  function setupSourceBuffer(preferredMime) {
    const codec = pickCodec(preferredMime)
    if (!codec) {
      onError('当前浏览器不支持该 WebM 编码，无法播放连麦合成流')
      return false
    }
    try {
      ms = new MediaSource()
    } catch (e) {
      onError('MediaSource 创建失败')
      return false
    }
    objectUrl = URL.createObjectURL(ms)
    video.src = objectUrl
    ms.addEventListener('sourceopen', () => {
      try {
        sb = ms.addSourceBuffer(codec)
        sb.mode = 'sequence'
        sb.addEventListener('updateend', onSbUpdateEnd)
      } catch (e) {
        onError('SourceBuffer 建立失败')
      }
    })
    ms.addEventListener('sourceended', () => onState('stopped'))
    ms.addEventListener('error', () => onError('媒体流错误'))
    video.addEventListener('waiting', () => onState('waiting'))
    video.play().catch(() => {})
    return true
  }

  function start() {
    if (running) {
      return
    }
    if (!mediaBase) {
      onError('缺少连麦合成流地址')
      return
    }
    if (!webmPlayerSupported()) {
      onError('当前浏览器不支持 MSE，无法播放连麦合成流')
      return
    }
    running = true
    initLoaded = false
    lastSeg = 0
    onState('starting')
    // 立刻探一次，之后定时轮询清单
    pollOnce()
    pollTimer = setInterval(pollOnce, POLL_MS)
  }

  function stop() {
    running = false
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = 0
    }
    appendQueue.length = 0
    if (sb) {
      try {
        sb.removeEventListener('updateend', onSbUpdateEnd)
        if (ms && ms.readyState === 'open') {
          ms.endOfStream()
        }
      } catch {
        /* 已结束 */
      }
      sb = null
    }
    if (ms) {
      try {
        if (video) {
          video.removeAttribute('src')
          video.load?.()
        }
        ms = null
      } catch {
        /* 忽略 */
      }
    }
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl)
      objectUrl = ''
    }
    onState('stopped')
  }

  return { start, stop }
}
