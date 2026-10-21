const { contextBridge, ipcRenderer, webUtils } = require('electron')

/**
 * 把主进程解析的后端地址暴露给渲染进程。
 *
 * main.js 通过 webPreferences.additionalArguments 传入 --server-base=xxx，
 * 这里从 process.argv 取出，用 contextBridge 挂到 window.__IM_SERVER__。
 * 注入发生在页面任何脚本执行之前，前端 utils/env.js 读它拼出 /api 与 ws 绝对地址；
 * 浏览器 Web 部署没有 preload，window.__IM_SERVER__ 不存在，前端自动回落到同源相对路径。
 *
 * ★ 但 argv 只能代表「建窗口那一刻」的值：用户在登录页改完后端地址只是 reload 页面，
 * 窗口对象并没有重建，additionalArguments 不会变。所以这里优先走同步通道
 * im:server-resolve 现取（每次页面加载都会跑一次 preload，拿到的就是最新解析结果），
 * argv 只当兜底（ipc 意外不可用时至少开发机还能按内置默认跑）。
 */
const FLAG = '--server-base='
const arg = process.argv.find((a) => a.startsWith(FLAG))
let baseUrl = arg ? decodeURIComponent(arg.slice(FLAG.length)) : ''
let source = 'argv'
try {
  const res = ipcRenderer.sendSync('im:server-resolve')
  if (res && res.ok && res.data && res.data.baseUrl) {
    baseUrl = res.data.baseUrl
    source = res.data.source
  }
} catch { /* 主进程未注册时回落到 argv 值 */ }

contextBridge.exposeInMainWorld('__IM_SERVER__', { baseUrl, source })

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
   * 后端地址的运行期配置桥（登录页「开发调试」与设置页「后端地址」行用它）。
   * 地址来自主进程的 server-config.js，渲染进程不自己存一份：连不上后端时
   * 这个弹窗是唯一出路，它必须不依赖任何网络请求就能读写。
   * set/reset 只落盘，生效靠前端 reload 页面（preload 会重新取一次）。
   */
  server: {
    /** → { baseUrl, source, builtIn, env, configPath, hasConfig } */
    get: () => call('im:server-get', null),
    /** 只校验规整不落盘 → { baseUrl }；前端拿它统一口径后再去探连接，避免两处各写一份规则 */
    normalize: (baseUrl) => call('im:server-normalize', { baseUrl }),
    /** 写入配置（主进程会校验并规整）→ { baseUrl, source:'file', configPath } */
    set: (baseUrl) => call('im:server-set', { baseUrl }),
    /** 删除配置文件，回落到环境变量或内置默认 → { baseUrl, source } */
    reset: () => call('im:server-reset', null),
    /** 在资源管理器里定位配置文件（没生成过则打开它所在目录） */
    reveal: () => call('im:server-reveal', null)
  },
  /**
   * 窗口内容防截图：true 时主进程对窗口置 SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)，
   * 截屏/录屏里变黑块，肉眼正常可见。拆闪照预览期间开、关掉预览即关。
   * 浏览器 Web 端没有这个桥，window.__IM_NATIVE__ 不存在，防截图仅桌面端生效。
   */
  setContentProtection: (enabled) => call('im:set-content-protection', { enabled }),
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
  },
  /**
   * 录屏审计桥：渲染进程用 MediaRecorder 录远控画面，分片经这里流式交给主进程写盘。
   * 只有桌面端有——浏览器既写不了任意磁盘目录，也不该在用户不知情下落地录像文件，
   * 前端 utils/recorder.js 拿不到这个对象就整体禁用该功能。
   */
  record: {
    /** 默认保存目录（系统「视频」\IM远程录屏），设置页留空时用它 */
    defaultDir: () => call('im:record-default-dir', null),
    /** 弹系统目录选择框，resolve 出所选路径；用户取消时为 null */
    pickDir: (current) => call('im:record-pick-dir', { current }),
    /** params: {dir, ext, meta} → {filePath, baseName} */
    start: (params) => call('im:record-start', params),
    /** data 为 MediaRecorder 分片的 ArrayBuffer，resolve 出已累计字节数 */
    chunk: (data) => call('im:record-chunk', { data }),
    /** params: {meta, auditEvents, reason} → {filePath, size, durationMs, sidecar}；无进行中的录制时为 null */
    stop: (params) => call('im:record-stop', params),
    state: () => call('im:record-state', null),
    /** 读回落盘录制文件 → {filePath, size, contentType, data:ArrayBuffer}；超 maxBytes 主进程报错 */
    read: (filePath, maxBytes) => call('im:record-read', { filePath, maxBytes }),
    /** 在资源管理器里定位录像文件；只给 dir 则打开目录本身 */
    openDir: (filePath, dir) => call('im:record-open-dir', { filePath, dir })
  }
})

/**
 * UDP 直连桥：只有桌面端有。
 *
 * 浏览器既开不了原始 UDP 也发不了裸 TCP，所以 Web 部署下 window.__IM_DIRECT__ 不存在，
 * 前端 utils/directChannel.js 据此跳过 UDP 打洞档（局域网 WebSocket 档不依赖它）。
 *
 * 契约：open/write/close 走 invoke（主进程统一返回 {ok, data|error}，本侧拆包），
 * onData/onState 是主进程推来的事件。打洞、DXP 分片、GBN 重传全在主进程做，
 * 渲染侧只收「完整消息」：text 给字符串、bin 给 ArrayBuffer（结构化克隆零拷贝）。
 */
contextBridge.exposeInMainWorld('__IM_DIRECT__', {
  isDesktop: true,
  /** params: {host, udpPort, token, aesKeyB64, sessionId, mtu, punchHost, punchPort} */
  open: (params) => call('im:direct-open', params),
  /** packet: {channel:'reliable'|'screen', kind:0|1, data:ArrayBuffer|Uint8Array} */
  write: (packet) => call('im:direct-write', packet),
  close: () => call('im:direct-close', null),
  stats: () => call('im:direct-stats', null),
  localCandidates: () => call('im:direct-local-candidates', null),
  onData: (callback) => {
    ipcRenderer.on('im:direct-data', (event, message) => callback(message))
  },
  onState: (callback) => {
    ipcRenderer.on('im:direct-state', (event, state) => callback(state))
  }
})

/**
 * 直播推流桥：只有桌面端有。
 *
 * 实际推流靠主进程 spawn 本机原生 ffmpeg 采集屏幕/摄像头、切 HLS(fMP4) 再 PUT 到 Nginx，
 * 渲染进程起不了子进程，故全部下沉到主进程（见 electron/live.js）。
 * 浏览器/手机端没有 preload，window.__IM_LIVE__ 不存在，前端据此禁用「开播」只保留观看。
 *
 * 契约：startPush/stopPush 走 invoke（主进程统一返回 {ok, data|error}，call 拆包）；
 * startPush 在 ffmpeg 成功 spawn 后即 resolve，推流中途的状态变化经 onState 事件持续推送
 * （state: starting | pushing | stopped | error，附带 encoder / error / warn 等字段）。
 *
 * stopPush 是「等回放定版完」才 resolve 的（排空上传队列 + PUT 一份 VOD 清单，一般百毫秒量级），
 * 所以返回 { stopped, replay }：replay 为 {segmentCount, durationSeconds, bytes, url} 或 null
 *（未生成/失败），前端拿它向后端上报「这一场有回放」。options.replay=false 可整体跳过。
 * ffmpeg 意外退出时主进程也会生成一次，统计随 onState 的 stopped 事件里的 replay 字段带上来。
 */
contextBridge.exposeInMainWorld('__IM_LIVE__', {
  isDesktop: true,
  /** 枚举本机显示器（多屏选择用）→ [{id, label, bounds, primary}] */
  listDisplays: () => call('im:live-list-displays', null),
  /** params: {pushUrl, sourceType, resolution, bitrateKbps, deviceName?, displayId?} → {started, encoder, tiers} */
  startPush: (params) => call('im:live-start-push', params),
  /** options: {replay?: boolean} → {stopped, replay} */
  stopPush: (options) => call('im:live-stop-push', options || null),
  queryState: () => call('im:live-query-state', null),
  onState: (callback) => {
    ipcRenderer.on('im:live-state', (event, state) => callback(state))
  }
})
