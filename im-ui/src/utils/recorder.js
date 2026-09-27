/**
 * 远控画面录屏审计（仅桌面端）。
 *
 * 录的是控制端画布上呈现的画面，也就是操作者本人看到的远端桌面：
 * 无论走中继还是直连、无论被控端发的是 JPEG 脏块还是 H.264 整帧，最终都汇到同一块
 * canvas 上，所以从 canvas 取流是唯一能覆盖全部链路的采集点。
 *
 * 为什么不在主进程直接录窗口：Electron 没有「录某个 webContents」的现成 API，
 * 走 desktopCapturer 又要弹系统级屏幕共享授权，且会把工具栏、侧边栏一起录进去，
 * 既侵犯操作者自己的隐私又让录像变大。canvas.captureStream 拿到的就是纯远端画面。
 *
 * 落盘链路：MediaRecorder 按 1 秒切片 → 分片串成 Promise 链逐个交给主进程 append。
 * 串行是必须的：invoke 并发发出时到达顺序不保证，乱序写盘会直接损坏容器结构。
 *
 * 浏览器端整体禁用：Web 部署没有 preload，拿不到 __IM_NATIVE__.record，
 * recordSupported() 返回 false，设置页相应控件置灰，会话里也不会启动录制。
 */
import { isElectron } from './env'

/** 录制帧率：审计只需看清操作轨迹，15fps 足够，再高只是白白放大文件 */
const RECORD_FPS = 15

/** 目标码率 2Mbps：1080p 屏幕内容下文字仍可辨认，约 15MB/分钟 */
const RECORD_BITS = 2 * 1000 * 1000

/** 切片间隔 1 秒：落盘越勤，进程被强杀时丢的画面越少，代价只是多几次 IPC */
const RECORD_TIMESLICE = 1000

/** 主进程录制桥；浏览器端为 null */
function bridge() {
  return (typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.record) || null
}

/** 当前环境能不能录屏：桌面端 + 桥可用 + 有 MediaRecorder */
export function recordSupported() {
  return isElectron() && !!bridge() && typeof window !== 'undefined' && typeof window.MediaRecorder === 'function'
}

/**
 * 容器与编码的候选表。
 *
 * 只用 WebM，不用 MP4，原因是实测出来的硬约束：Chromium 的 MediaRecorder 在 MP4 下
 * 不按 timeslice 吐片，整段攒到 stop() 才一次性给出（6 秒录像的 1.27MB 全在最后一刻到达），
 * 而本模块的落盘方式是「分片到了就追加写盘」。选 MP4 等于让录像全程堆在渲染进程内存里，
 * 两小时的会话按 2Mbps 算要占 ~1.8GB，必然把远控本身拖垮。WebM 则是每秒稳定吐一片。
 *
 * vp9 排在 vp8 前面同样是实测结论（1920x1080@15、2Mbps、高熵画面各录 8 秒）：
 * 两者都每秒稳定吐片、都不丢帧（15.2 / 15.1 fps），但 vp9 产物 2.62MB、vp8 4.59MB，
 * 小 43%。vp9 软编更吃 CPU，好在编码不在主线程，实测帧率没有掉。
 *
 * 代价是 .webm 不能用 Windows 自带播放器直接打开（浏览器拖进去或 VLC 可以）。
 * 审计录像首先要保证长时间会话不炸内存，播放便利性排第二。
 */
const MIME_CANDIDATES = [
  { mime: 'video/webm;codecs=vp9', ext: 'webm' },
  { mime: 'video/webm;codecs=vp8', ext: 'webm' },
  { mime: 'video/webm', ext: 'webm' }
]

/** 选一个本端确实支持的容器，全都不支持时返回空串交给 MediaRecorder 自己决定 */
export function pickRecordingType() {
  if (typeof window === 'undefined' || typeof window.MediaRecorder !== 'function') {
    return { mime: '', ext: 'webm' }
  }
  for (const candidate of MIME_CANDIDATES) {
    try {
      if (window.MediaRecorder.isTypeSupported(candidate.mime)) {
        return candidate
      }
    } catch {
      // 个别实现里 isTypeSupported 会对畸形 mime 抛异常，跳过这个候选即可
    }
  }
  return { mime: '', ext: 'webm' }
}

/* ------------------------------ 设置页用的目录能力 ------------------------------ */

/** 默认保存目录（系统「视频」\IM远程录屏）；浏览器端返回空串 */
export function fetchDefaultRecordDir() {
  const api = bridge()
  return api ? api.defaultDir() : Promise.resolve('')
}

/** 弹系统目录选择框，返回所选路径；用户取消或浏览器端返回 null */
export function pickRecordDir(current) {
  const api = bridge()
  return api ? api.pickDir(current || '') : Promise.resolve(null)
}

/**
 * 在资源管理器里打开录制目录。
 * @param {string} [filePath] 传了就选中该录像文件（会话结束后的「打开录像」）
 * @param {string} [dir] 只开目录时用；两者都留空则落到默认目录
 */
export function openRecordDir(filePath, dir) {
  const api = bridge()
  return api ? api.openDir(filePath || '', dir || '') : Promise.resolve(false)
}

/* ------------------------------ 会话录制器 ------------------------------ */

/**
 * 创建一路会话录制器。一次远控会话对应一个实例，用完即弃。
 *
 * 设计取向：录制是旁路能力，任何失败都不能影响远控本身——
 * 所有异常都收敛成 onError 回调 + stopped 状态，绝不往外抛。
 *
 * @param {{ onError?: (message: string) => void, onBytes?: (bytes: number) => void }} hooks
 */
export function createSessionRecorder(hooks = {}) {
  const api = bridge()
  const onError = typeof hooks.onError === 'function' ? hooks.onError : () => {}
  const onBytes = typeof hooks.onBytes === 'function' ? hooks.onBytes : () => {}

  let recorder = null
  let stream = null
  let filePath = ''
  let startedAt = 0
  let bytes = 0
  let broken = false
  // 分片写入的串行链：每个 ondataavailable 都同步往链尾追加，保证顺序与「等尾巴写完再收口」
  let chain = Promise.resolve()

  function fail(message) {
    if (broken) {
      return
    }
    broken = true
    onError(message)
    // 已经写不进去了就别再白耗 CPU 编码：就地停掉编码器。
    // 注意不能把 recorder 置空——文件收口统一由 stop() 做，那里要靠这个引用拿到 state
    try {
      if (recorder && recorder.state !== 'inactive') {
        recorder.stop()
      }
    } catch {
      /* 已停或状态异常，交给 stop() 的超时兜底 */
    }
  }

  /** 把一个 Blob 分片接到链尾：转 ArrayBuffer + IPC 写盘，失败只标记不中断链 */
  function enqueue(blob) {
    chain = chain
      .then(async () => {
        if (broken || !blob || !blob.size) {
          return
        }
        const buffer = await blob.arrayBuffer()
        bytes = await api.chunk(buffer)
        onBytes(bytes)
      })
      .catch((error) => fail(String((error && error.message) || error)))
  }

  /**
   * 开始录制。
   * @param {HTMLCanvasElement} canvas 远控画面画布
   * @param {{ dir?: string, meta?: object }} options dir 留空则用主进程默认目录
   * @returns {Promise<boolean>} 是否真的录起来了
   */
  async function start(canvas, options = {}) {
    if (!api || broken || recorder) {
      return false
    }
    if (!canvas || typeof canvas.captureStream !== 'function') {
      fail('当前环境不支持画布取流')
      return false
    }
    // 画布宽高为 0 时 captureStream 会抛 InvalidStateError：远端还没发过第一帧就是这样，
    // 这种情况直接判为「本轮不录」，等下次会话再试，不值得报错打扰用户
    if (!canvas.width || !canvas.height) {
      fail('画面尚未就绪，本次会话未录制')
      return false
    }

    const type = pickRecordingType()
    let opened = null
    try {
      opened = await api.start({ dir: options.dir || '', ext: type.ext, meta: options.meta || {} })
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
      stream = canvas.captureStream(RECORD_FPS)
      recorder = new MediaRecorder(stream, {
        mimeType: type.mime || undefined,
        videoBitsPerSecond: RECORD_BITS
      })
    } catch (error) {
      // 取流或建 recorder 失败：把已经创建的空文件收掉，别在目录里留 0 字节垃圾
      recorder = null
      releaseStream()
      try {
        await api.stop({ reason: 'recorder-init-failed' })
      } catch {
        /* 收不掉也不影响后续，主进程退出时还会兜一次 */
      }
      filePath = ''
      fail(`录制器初始化失败：${(error && error.message) || error}`)
      return false
    }

    recorder.ondataavailable = (event) => enqueue(event.data)
    recorder.onerror = () => fail('录制中断（编码器报错），已保留此前画面')
    startedAt = Date.now()
    try {
      recorder.start(RECORD_TIMESLICE)
    } catch (error) {
      recorder = null
      releaseStream()
      fail(`录制启动失败：${(error && error.message) || error}`)
      return false
    }
    return true
  }

  function releaseStream() {
    if (stream) {
      stream.getTracks().forEach((track) => track.stop())
      stream = null
    }
  }

  /**
   * 停止录制并等最后一个分片落盘。
   * @param {{ meta?: object, auditEvents?: Array, reason?: string }} payload
   * @returns {Promise<{ filePath: string, size: number, durationMs: number, sidecar: string|null }|null>}
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
        releaseStream()
        // 等链子排空再收口：onstop 之前最后一个分片已经挂上链，这里能确保它写完
        await chain.catch(() => {})
        try {
          const result = await api.stop({
            meta: payload.meta || null,
            auditEvents: payload.auditEvents || [],
            reason: payload.reason || ''
          })
          resolve(result || null)
        } catch (error) {
          fail(`录制文件收尾失败：${(error && error.message) || error}`)
          resolve(null)
        }
      }
      current.onstop = finish
      // 编码器已经报错时 stop() 可能不再触发 onstop，兜一个超时避免永远悬着
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

  return {
    start,
    stop,
    /** 是否正在录（编码器已报错时为 false，界面据此撤掉红点） */
    get active() {
      return !!recorder && !broken
    },
    get path() {
      return filePath
    },
    get elapsedMs() {
      return startedAt ? Date.now() - startedAt : 0
    }
  }
}
