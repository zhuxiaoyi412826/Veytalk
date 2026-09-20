const { contextBridge, ipcRenderer, webUtils } = require('electron')

/**
 * 把主进程透传的后端地址暴露给渲染进程。
 *
 * main.js 通过 webPreferences.additionalArguments 传入 --server-base=xxx，
 * 这里从 process.argv 取出，用 contextBridge 挂到 window.__IM_SERVER__。
 * 注入发生在页面任何脚本执行之前，前端 utils/env.js 读它拼出 /api 与 ws 绝对地址；
 * 浏览器 Web 部署没有 preload，window.__IM_SERVER__ 不存在，前端自动回落到同源相对路径。
 */
const FLAG = '--server-base='
const arg = process.argv.find((a) => a.startsWith(FLAG))
const baseUrl = arg ? decodeURIComponent(arg.slice(FLAG.length)) : ''

contextBridge.exposeInMainWorld('__IM_SERVER__', { baseUrl })

/**
 * 桌面端原生能力桥。目前只用于调用主进程的本机 ffmpeg（GPU 硬件编码）压缩视频。
 * 浏览器 Web 部署没有 preload，window.__IM_NATIVE__ 不存在，前端据此走「不压缩、直传原片」。
 */
let compressSeq = 0

/**
 * 主进程 IPC 的统一拆包：handler 返回 { ok, data | error }，
 * 失败时在本侧抛带原始信息的 Error，让调用方能拿到根因而不是
 * 「Error invoking remote method」一句废话。
 */
async function call(channel, payload) {
  const res = await ipcRenderer.invoke(channel, payload)
  if (!res || !res.ok) {
    throw new Error((res && res.error) || `IPC ${channel} 调用失败`)
  }
  return res.data
}

contextBridge.exposeInMainWorld('__IM_NATIVE__', {
  isDesktop: true,
  /**
   * 取 File 的真实磁盘路径。Electron 32+ 移除了 File.path，必须在 preload 里用 webUtils 取；
   * 主进程拿到路径后能让 ffmpeg 直接流式读写磁盘，不必把大视频读进内存。
   */
  getPathForFile: (file) => {
    try { return webUtils.getPathForFile(file) || '' } catch { return '' }
  },
  /**
   * 压缩视频：把 inputPath 交给主进程原生 ffmpeg 处理，onProgress 收 0-100 进度。
   * resolve 出 { ok, compressed, name, size, data }；data 是压缩后字节的 Uint8Array，
   * compressed:false 表示压缩无收益/不可用，前端应回退原片直传。
   */
  compressVideo: (inputPath, options, onProgress) => {
    const requestId = `c${Date.now()}_${compressSeq++}`
    const handler = (e, msg) => {
      if (!msg || msg.requestId !== requestId) return
      if (typeof onProgress === 'function') onProgress(msg.percent)
    }
    ipcRenderer.on('im:compress-progress', handler)
    return ipcRenderer
      .invoke('im:compress-video', { inputPath, requestId, duration: (options && options.duration) || 0 })
      .finally(() => ipcRenderer.removeListener('im:compress-progress', handler))
  },
  /**
   * 本地消息库桥：渲染进程不碰 sqlite 文件，所有 SQL 经主进程执行（架构约束）。
   * statements 是 [[sql, params], ...]，主进程在同一事务里执行完再原子落盘。
   */
  db: {
    open: (userId) => call('im:db-open', { userId }),
    close: () => call('im:db-close', null),
    all: (sql, params) => call('im:db-all', { sql, params }),
    write: (statements) => call('im:db-write', { statements }),
    info: () => call('im:db-info', null),
    destroy: () => call('im:db-destroy', null)
  },
  /**
   * 媒体缓存桥：二进制存主进程的 cache/media 目录（与消息 DB 物理隔离），
   * 命中时主进程顺手 touch 时间戳供 LRU；data 经结构化克隆以 ArrayBuffer 往返。
   */
  media: {
    get: async (key) => {
      const hit = await call('im:media-get', { key })
      if (!hit) return null
      return { data: new Blob([hit.data]), etag: hit.etag, mime: hit.mime }
    },
    put: async (key, blob, etag, mime) => {
      const buffer = await blob.arrayBuffer()
      return call('im:media-put', { key, data: buffer, etag, mime })
    },
    remove: (key) => call('im:media-remove', { key }),
    clear: () => call('im:media-clear', null),
    stats: () => call('im:media-stats', null),
    trim: (maxBytes) => call('im:media-trim', { maxBytes })
  }
})
