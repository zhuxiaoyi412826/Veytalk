/**
 * 通话录制编排（仅桌面端）：把一路 1:1 通话的本端/对端画面与上下行声音合成一个 WebM 落盘。
 *
 * 与远控录屏（utils/recorder.js）同源复用主进程录制桥 __IM_NATIVE__.record：
 * 「MediaRecorder 按 1 秒切片 → 分片串行 append 到磁盘」这套落盘链路对编码无感知，音频轨天然进容器，
 * 所以这里只解决远控录屏没有的两件事：① 把本地预览 + 远端画面 +（可选）屏幕共享绘制到一块 canvas 取视频轨；
 * ② 用 AudioContext 把本端麦克风与对端声音混成一路音频轨。二者合并成一个 MediaStream 交给 MediaRecorder。
 *
 * 为什么坚持 WebM 而不是 MP4：Chromium 的 MediaRecorder 在 MP4 下不按 timeslice 吐片，整段攒到 stop() 才给，
 * 与「分片到达即写盘」冲突，长时间通话会把录像全堆进渲染进程内存。WebM 每秒稳定吐一片（见 utils/recorder.js 注释）。
 *
 * 浏览器 Web 端整体禁用：没有 preload，拿不到 __IM_NATIVE__.record，callRecordSupported() 返回 false，
 * 通话仍可正常接通，只是不落盘——与远控/审核录屏的降级策略一致。
 */
import { isElectron } from './env'
import { pickRecordingType } from './recorder'

/** 视频帧率：通话录像用于留存，15fps 足够，再高只白白放大体积 */
const VIDEO_FPS = 15
/** 视频目标码率 1Mbps：720p 人脸/共享文字可辨，约 7.5MB/分钟 */
const VIDEO_BITS = 1 * 1000 * 1000
/** 切片间隔 1 秒：与远控录屏同参，进程被强杀时最多丢一秒 */
const TIMESLICE = 1000
/** 合成画布尺寸：远端大画面 16:9，本地小窗右下角 PIP */
const CANVAS_W = 1280
const CANVAS_H = 720

function bridge() {
  return (typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.record) || null
}

/** 当前环境能不能录制通话：桌面端 + 桥可用 + 有 MediaRecorder */
export function callRecordSupported() {
  return isElectron() && !!bridge() && typeof window !== 'undefined' && typeof window.MediaRecorder === 'function'
}

/**
 * 创建一路通话录制器。一次通话对应一个实例，挂断即弃。
 * 录制是旁路能力：任何失败收敛成 onError 回调，绝不影响通话本身。
 * @param {{ onError?: (msg: string) => void, onBytes?: (bytes: number) => void }} hooks
 */
export function createCallRecorder(hooks = {}) {
  const api = bridge()
  const onError = typeof hooks.onError === 'function' ? hooks.onError : () => {}
  const onBytes = typeof hooks.onBytes === 'function' ? hooks.onBytes : () => {}

  let recorder = null
  let combinedStream = null
  let canvas = null
  let rafId = 0
  let audioCtx = null
  let filePath = ''
  let startedAt = 0
  let broken = false
  let chain = Promise.resolve()

  function fail(message) {
    if (broken) {
      return
    }
    broken = true
    onError(message)
    try {
      if (recorder && recorder.state !== 'inactive') {
        recorder.stop()
      }
    } catch {
      /* 交给 stop() 收口 */
    }
  }

  /** 分片串行落盘链：转 ArrayBuffer → IPC append，保证写入顺序与容器完整 */
  function enqueue(blob) {
    chain = chain
      .then(async () => {
        if (broken || !blob || !blob.size) {
          return
        }
        const buffer = await blob.arrayBuffer()
        const total = await api.chunk(buffer)
        onBytes(total)
      })
      .catch((error) => fail(String((error && error.message) || error)))
  }

  /** 把一路流的视频绘制进 canvas：remote 全屏、local 右下 PIP；纯音频无操作 */
  function paint(ctx, remoteVideo, localVideo, hasLocalVideo) {
    const w = CANVAS_W
    const h = CANVAS_H
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, w, h)
    if (remoteVideo && remoteVideo.videoWidth) {
      drawCover(ctx, remoteVideo, 0, 0, w, h)
    }
    if (hasLocalVideo && localVideo && localVideo.videoWidth) {
      const pw = Math.round(w * 0.25)
      const ph = Math.round(pw * 9 / 16)
      ctx.strokeStyle = 'rgba(255,255,255,0.6)'
      ctx.strokeRect(w - pw - 16 - 1, h - ph - 16 - 1, pw + 2, ph + 2)
      drawContain(ctx, localVideo, w - pw - 16, h - ph - 16, pw, ph)
    }
  }

  function drawCover(ctx, video, x, y, w, h) {
    const vr = video.videoWidth / video.videoHeight
    const cr = w / h
    let sw = video.videoWidth
    let sh = video.videoHeight
    if (vr > cr) {
      sw = video.videoHeight * cr
    } else {
      sh = video.videoWidth / cr
    }
    const sx = (video.videoWidth - sw) / 2
    const sy = (video.videoHeight - sh) / 2
    ctx.drawImage(video, sx, sy, sw, sh, x, y, w, h)
  }

  function drawContain(ctx, video, x, y, w, h) {
    const vr = video.videoWidth / video.videoHeight
    let dw = w
    let dh = h
    if (vr > w / h) {
      dh = w / vr
    } else {
      dw = h * vr
    }
    ctx.drawImage(video, (w - dw) / 2, (h - dh) / 2, dw, dh)
  }

  /**
   * 开始录制。
   * @param {{ localStream?: MediaStream, remoteStream?: MediaStream, hasVideo: boolean, dir?: string, meta?: object }} opts
   * @returns {Promise<boolean>}
   */
  async function start(opts = {}) {
    if (!api || broken || recorder) {
      return false
    }
    const type = pickRecordingType()
    let opened = null
    try {
      opened = await api.start({ dir: opts.dir || '', ext: type.ext, meta: opts.meta || {} })
    } catch (error) {
      fail(`录制文件创建失败：${(error && error.message) || error}`)
      return false
    }
    if (!opened || !opened.filePath) {
      fail('录制文件创建失败')
      return false
    }
    filePath = opened.filePath

    try {
      const tracks = []

      // ---- 视频：有画面才建 canvas 取流；纯音频跳过 ----
      if (opts.hasVideo) {
        canvas = document.createElement('canvas')
        canvas.width = CANVAS_W
        canvas.height = CANVAS_H
        const ctx = canvas.getContext('2d')
        const remoteVideo = hiddenVideo(opts.remoteStream)
        const localVideo = hiddenVideo(opts.localStream)
        const hasLocalVideo = !!(opts.localStream && opts.localStream.getVideoTracks().length)
        const draw = () => {
          if (broken || !canvas) {
            return
          }
          paint(ctx, remoteVideo, localVideo, hasLocalVideo)
          rafId = requestAnimationFrame(draw)
        }
        rafId = requestAnimationFrame(draw)
        const canvasStream = canvas.captureStream(VIDEO_FPS)
        canvasStream.getVideoTracks().forEach((t) => tracks.push(t))
      }

      // ---- 音频：AudioContext 混本端麦克风 + 对端声音为一路 ----
      const audioTracks = collectAudioTracks(opts.localStream, opts.remoteStream)
      if (audioTracks.length) {
        audioCtx = new AudioContext()
        const dest = audioCtx.createMediaStreamDestination()
        audioTracks.forEach((stream) => {
          try {
            audioCtx.createMediaStreamSource(stream).connect(dest)
          } catch {
            /* 单路接不上不影响其余 */
          }
        })
        dest.stream.getAudioTracks().forEach((t) => tracks.push(t))
      }

      if (!tracks.length) {
        throw new Error('没有可录制的媒体轨道')
      }
      combinedStream = new MediaStream(tracks)
      recorder = new MediaRecorder(combinedStream, {
        mimeType: type.mime || undefined,
        videoBitsPerSecond: VIDEO_BITS
      })
      recorder.ondataavailable = (event) => enqueue(event.data)
      recorder.onerror = () => fail('录制中断（编码器报错）')
      startedAt = Date.now()
      recorder.start(TIMESLICE)
      return true
    } catch (error) {
      cleanupMedia()
      try {
        await api.stop({ reason: 'recorder-init-failed' })
      } catch {
        /* 收不掉留给主进程兜底 */
      }
      filePath = ''
      fail(`录制器初始化失败：${(error && error.message) || error}`)
      return false
    }
  }

  function collectAudioTracks(...streams) {
    return streams.filter((s) => s && s.getAudioTracks && s.getAudioTracks().length)
  }

  function hiddenVideo(stream) {
    if (!stream || !stream.getVideoTracks().length) {
      return null
    }
    const el = document.createElement('video')
    el.muted = true
    el.playsInline = true
    el.srcObject = stream
    el.play().catch(() => {})
    return el
  }

  function cleanupMedia() {
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    if (audioCtx) {
      audioCtx.close().catch(() => {})
      audioCtx = null
    }
    if (combinedStream) {
      combinedStream.getTracks().forEach((t) => t.stop())
      combinedStream = null
    }
    canvas = null
  }

  /**
   * 停止录制并等最后一个分片落盘。
   * @returns {Promise<{ filePath: string, size: number, durationMs: number, sidecar: string }|null>}
   */
  function stop(payload = {}) {
    if (!api || !recorder) {
      return Promise.resolve(null)
    }
    const current = recorder
    recorder = null
    return new Promise((resolve) => {
      let settled = false
      const finish = async () => {
        if (settled) {
          return
        }
        settled = true
        cleanupMedia()
        await chain.catch(() => {})
        try {
          const result = await api.stop({ meta: payload.meta || null, auditEvents: [], reason: payload.reason || '' })
          resolve(result || null)
        } catch (error) {
          fail(`录制文件收尾失败：${(error && error.message) || error}`)
          resolve(null)
        }
      }
      current.onstop = finish
      setTimeout(finish, 3000)
      try {
        if (current.state !== 'inactive') {
          current.stop()
        } else {
          finish()
        }
      } catch {
        finish()
      }
    })
  }

  /**
   * 读回落盘文件为 File 对象，供 uploadFileSmart 上传。
   * 主进程在 im:record-read 里已按 maxBytes 拦截超限，这里把 'CALL_RECORD_TOO_LARGE' 透传给调用方提示。
   * @returns {Promise<File>}
   */
  async function readAsFile(maxBytes) {
    if (!api || !filePath) {
      throw new Error('没有可读取的录制文件')
    }
    const result = await api.read(filePath, maxBytes || 0)
    const name = filePath.replace(/^.*[\\/]/, '')
    return new File([result.data], name, { type: result.contentType || 'video/webm' })
  }

  return {
    start,
    stop,
    readAsFile,
    get active() {
      return !!recorder && !broken
    },
    get path() {
      return filePath
    }
  }
}
