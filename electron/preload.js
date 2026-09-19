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
  }
})
