const { app, BrowserWindow, Menu, session, dialog, ipcMain } = require('electron')
const path = require('path')
const os = require('os')
const fs = require('fs')
const { spawn, execFile } = require('child_process')
const { setupLocalDb } = require('./localdb')

/**
 * 远程后端根地址（协议 + host + 端口，不含 /api、不含 /ws）。
 *
 * Electron 桌面端不再像浏览器那样与后端同源（没有 vite proxy / Nginx 反代），
 * 前端必须拿到后端的绝对地址才能发请求。这个值经 preload 用 additionalArguments
 * 注入到渲染进程的 window.__IM_SERVER__.baseUrl，前端 utils/env.js 据此拼 /api 与 ws。
 *
 * ★ 打包分发前，把它改成你实际部署后端的地址，例如 'http://192.168.1.10:8080'
 *   或 'https://im.example.com'。改完重新 npm run build 即可，前端代码无需改动。
 */
const SERVER_BASE = 'http://localhost:8080'

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    title: 'IM 即时通讯',
    backgroundColor: '#ffffff',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // 桌面端加载的是本地可信 dist，但要从 file:// 跨源访问远程后端（含带自定义头
      // satoken/X-Trace-Id 的预检请求）。关掉同源策略可免去后端 CORS 配置与预检失败问题；
      // 若后端已正确配置 CORS，可改回 true 获得更严格的安全边界。
      webSecurity: false,
      // 把后端地址透传给 preload，再由 contextBridge 暴露给渲染进程
      additionalArguments: [`--server-base=${SERVER_BASE}`]
    }
  })

  Menu.setApplicationMenu(null)
  win.loadFile(path.join(__dirname, 'dist', 'index.html'))
}

/**
 * 文件下载处理。
 *
 * Electron 的渲染进程不会像浏览器那样自动接管 <a download> / 直链下载，
 * 不监听 will-download 的话，点下载会「没反应」（和手机端那个大文件下载 bug 同源）。
 * 这里弹系统保存框让用户选路径，交给 Chromium 下载器流式写盘，1GB 大文件也不占内存。
 */
function setupDownload() {
  session.defaultSession.on('will-download', (event, item) => {
    const savePath = dialog.showSaveDialogSync({
      title: '保存文件',
      defaultPath: item.getFilename() || 'download'
    })
    if (!savePath) {
      item.cancel()
      return
    }
    item.setSavePath(savePath)
  })
}

/* ======================= 视频硬件压缩（调用本机原生 ffmpeg） =======================
 * 桌面端专属：渲染进程受 contextIsolation:true / nodeIntegration:false 限制，不能直接起子进程，
 * 所以压缩全在主进程做——渲染进程经 preload 把视频的真实磁盘路径发过来，这里 spawn 原生 ffmpeg，
 * 用 GPU 硬件编码器（NVENC/QuickSync/AMF）转成 720p MP4，缺对应 GPU/驱动时自动回落 libx264 软编。
 * ffmpeg 直接读写磁盘、流式处理，2GB/4K 也不占内存，这是浏览器端 ffmpeg.wasm 做不到的。
 * 找不到 ffmpeg 时压缩自动禁用，视频原片直传（和 Web 端一致），不影响其它功能。
 */

/**
 * 定位 ffmpeg 可执行文件，按优先级：
 *   1) 打包内置的 resources/ffmpeg/ffmpeg.exe（electron-builder 的 extraResources 拷进去的）
 *   2) 开发期 electron/bin/ffmpeg.exe
 *   3) 环境变量 IM_FFMPEG_PATH 显式指定
 *   4) 系统 PATH 里的 ffmpeg（用户自己装过）
 */
function resolveFfmpegPath() {
  const candidates = []
  if (process.resourcesPath) {
    candidates.push(path.join(process.resourcesPath, 'ffmpeg', 'ffmpeg.exe'))
  }
  candidates.push(path.join(__dirname, 'bin', 'ffmpeg.exe'))
  if (process.env.IM_FFMPEG_PATH) {
    candidates.push(process.env.IM_FFMPEG_PATH)
  }
  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) return c
    } catch { /* 忽略单个候选探测失败 */ }
  }
  return 'ffmpeg' // 交给系统 PATH；若确实没有，spawn 会失败并被兜底成「压缩不可用」
}

/** libx264 软件编码参数，作为硬件编码不可用/失败时的兜底 */
const SOFTWARE_ENCODER = { name: 'libx264', hw: false, args: ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '28'] }

/** 选定编码器的缓存，避免每次压缩都重新探测 */
let encoderCache = null

/**
 * 探测可用编码器：优先 GPU 硬件（nvenc > qsv > amf），都没有则 libx264。
 * 注意 -encoders 只反映「编译时是否包含」，不代表本机一定装了对应 GPU/驱动，
 * 所以硬件编码在 runFfmpeg 运行失败时还会再回落一次软编（见 handler）。
 */
function detectEncoder(ffmpegPath) {
  if (encoderCache) return Promise.resolve(encoderCache)
  return new Promise((resolve) => {
    let settled = false
    const done = (v) => { if (!settled) { settled = true; encoderCache = v; resolve(v) } }
    try {
      const proc = execFile(ffmpegPath, ['-hide_banner', '-encoders'], { timeout: 8000 }, (err, stdout) => {
        if (err) return done(SOFTWARE_ENCODER)
        const out = String(stdout || '')
        if (/\bh264_nvenc\b/.test(out)) return done({ name: 'h264_nvenc', hw: true, args: ['-c:v', 'h264_nvenc', '-preset', 'medium', '-rc', 'vbr', '-cq', '28', '-b:v', '0'] }) // 旧式 preset+显式 CQ：Maxwell(GTX900)/Pascal 老卡也吃（新式 p1-p7 部分老驱动不认）
        if (/\bh264_qsv\b/.test(out)) return done({ name: 'h264_qsv', hw: true, args: ['-c:v', 'h264_qsv', '-global_quality', '28'] })
        if (/\bh264_amf\b/.test(out)) return done({ name: 'h264_amf', hw: true, args: ['-c:v', 'h264_amf', '-quality', 'balanced'] })
        return done(SOFTWARE_ENCODER)
      })
      proc.on('error', () => done(SOFTWARE_ENCODER))
    } catch { done(SOFTWARE_ENCODER) }
  })
}

/** 探测视频时长（秒），把 ffmpeg 的 out_time_ms 换算成百分比进度用。读不到返回 0（进度不精确，不影响结果）。 */
function probeDuration(ffmpegPath, inputPath) {
  return new Promise((resolve) => {
    let settled = false
    const done = (v) => { if (!settled) { settled = true; resolve(v) } }
    try {
      // 只给 -i 不给输出，ffmpeg 会以非 0 退出，但已把 Duration 打进 stderr
      const proc = execFile(ffmpegPath, ['-hide_banner', '-i', inputPath], { timeout: 15000 }, (err, stdout, stderr) => {
        const text = String(stderr || '') + String(stdout || '')
        const m = text.match(/Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/)
        done(m ? (+m[1] * 3600 + +m[2] * 60 + parseFloat(m[3])) : 0)
      })
      proc.on('error', () => done(0))
    } catch { done(0) }
  })
}

/**
 * 执行一次 ffmpeg 转码，resolve { code, stderrTail }；期间用 onProgress(percent) 回报进度。
 * 靠 -progress pipe:1 让 ffmpeg 把进度以 key=value 写进 stdout，逐行取 out_time_ms 换算百分比。
 */
function runFfmpeg(ffmpegPath, args, durationSec, onProgress) {
  return new Promise((resolve) => {
    let proc
    try {
      proc = spawn(ffmpegPath, args, { windowsHide: true })
    } catch (e) {
      return resolve({ code: -1, stderrTail: String((e && e.message) || e) })
    }
    let stderrTail = ''
    proc.stdout.on('data', (buf) => {
      const matches = buf.toString().match(/out_time_ms=(\d+)/g)
      if (matches && durationSec > 0) {
        const us = parseInt(matches[matches.length - 1].split('=')[1], 10)
        const pct = Math.max(0, Math.min(99, Math.round((us / 1e6 / durationSec) * 100)))
        if (onProgress) onProgress(pct)
      }
    })
    proc.stderr.on('data', (buf) => { stderrTail = (stderrTail + buf.toString()).slice(-2000) })
    proc.on('error', (e) => resolve({ code: -1, stderrTail: String((e && e.message) || e) }))
    proc.on('close', (code) => resolve({ code, stderrTail }))
  })
}

/** 注册视频压缩 IPC：渲染进程 invoke('im:compress-video')，进度经 'im:compress-progress' 事件回推。 */
function setupVideoCompress() {
  ipcMain.handle('im:compress-video', async (event, payload) => {
    const { inputPath, requestId, duration } = payload || {}
    const send = (percent) => {
      try { event.sender.send('im:compress-progress', { requestId, percent }) } catch { /* 窗口已关闭 */ }
    }
    let outputPath = null
    try {
      if (!inputPath || !fs.existsSync(inputPath)) {
        return { ok: false, reason: 'input-not-found' }
      }
      const ffmpegPath = resolveFfmpegPath()
      const enc = await detectEncoder(ffmpegPath)
      const inputSize = fs.statSync(inputPath).size
      const baseName = path.basename(inputPath).replace(/\.[^.]+$/, '') || 'video'
      outputPath = path.join(os.tmpdir(), `im-compress-${Date.now()}-${Math.round(Math.random() * 1e6)}.mp4`)

      const durSec = duration > 0 ? duration : await probeDuration(ffmpegPath, inputPath)
      const tail = ['-vf', 'scale=-2:720', '-c:a', 'aac', '-b:a', '128k', '-movflags', '+faststart', '-progress', 'pipe:1', '-y', outputPath]

      let res = await runFfmpeg(ffmpegPath, ['-hide_banner', '-i', inputPath, ...enc.args, ...tail], durSec, send)
      // 硬件编码器编译进来了但本机没对应 GPU/驱动时会失败，回落软编再试一次
      if (res.code !== 0 && enc.hw) {
        encoderCache = SOFTWARE_ENCODER
        res = await runFfmpeg(ffmpegPath, ['-hide_banner', '-i', inputPath, ...SOFTWARE_ENCODER.args, ...tail], durSec, send)
      }

      if (res.code !== 0 || !fs.existsSync(outputPath)) {
        return { ok: false, reason: (res.stderrTail || 'ffmpeg-failed').slice(-500) }
      }

      const outSize = fs.statSync(outputPath).size
      // 压完反而没变小（源本就是高压缩视频）就不用压缩结果，让前端传原片
      if (outSize <= 0 || outSize >= inputSize) {
        return { ok: true, compressed: false, name: `${baseName}.mp4`, size: outSize }
      }

      const data = fs.readFileSync(outputPath) // 压缩产物通常只有几 MB~几十 MB，读进内存回传无压力
      send(100)
      return { ok: true, compressed: true, name: `${baseName}.mp4`, size: outSize, data }
    } catch (e) {
      return { ok: false, reason: String((e && e.message) || e) }
    } finally {
      if (outputPath) { try { fs.unlinkSync(outputPath) } catch { /* 已删或不存在 */ } }
    }
  })
}

/* ======================= 被控端 Agent 随桌面端自启 =======================
 * 打包安装包经 extraResources 内置了被控端 Agent（resources/agent/im-remote-agent.jar）
 * 与裁剪 JRE（resources/jre）。桌面端启动时后台拉起 Agent，被控机装完包无需再手动
 * 双击 bat：Agent 起身后按识别码模式自动连中继，网页绿色「本机识别码」面板随即
 * 经回环接口读到识别码。
 *
 * 防重复启动：先探测 Agent 回环接口（127.0.0.1:18923/local-info），有响应即认为
 * 已在运行（自启过 / bat 拉过 / 手动跑的 jar 都算）直接跳过，避免第二个 JVM 去争
 * WS 连接与回环端口。Agent 生命周期独立于 IM 窗口：关窗口不杀它，被控机才能保持
 * 可远程；要停请在 Agent 窗口退出或任务管理器结束 javaw.exe。
 * 开发模式下 electron/ 目录只有 jre 没有 agent/ jar，探测自然跳过，不影响调试。
 */
const AGENT_LOCAL_INFO_URL = 'http://127.0.0.1:18923/local-info'

/** 定位内置 JRE 与 Agent jar：优先安装包 resources 目录，回退主脚本同目录 */
function resolveAgentPaths() {
  const roots = []
  if (process.resourcesPath) roots.push(process.resourcesPath)
  roots.push(__dirname)
  for (const root of roots) {
    const javaw = path.join(root, 'jre', 'bin', 'javaw.exe')
    const jar = path.join(root, 'agent', 'im-remote-agent.jar')
    try {
      if (fs.existsSync(javaw) && fs.existsSync(jar)) return { javaw, jar }
    } catch { /* 忽略单个候选探测失败 */ }
  }
  return null
}

/** 探测本机 Agent 是否已在运行：回环接口有响应即在运行 */
function probeLocalAgent() {
  return fetch(AGENT_LOCAL_INFO_URL, { signal: AbortSignal.timeout(800) })
    .then((r) => r.ok)
    .catch(() => false)
}

/** 后台拉起 Agent：工作目录固定 %USERPROFILE%\im-remote-agent（与 bat 一致，配置和日志同落一处） */
async function setupAgentAutostart() {
  const paths = resolveAgentPaths()
  if (!paths) return
  if (await probeLocalAgent()) return
  const workDir = path.join(os.homedir(), 'im-remote-agent')
  try {
    fs.mkdirSync(workDir, { recursive: true })
    // 清掉宿主机的 JAVA_TOOL_OPTIONS 再传给内置 JRE：例如 trustStoreType=WINDOWS-ROOT
    // 依赖 jdk.crypto.mscapi 模块，环境残留会让 Agent 起身即死
    const env = { ...process.env }
    delete env.JAVA_TOOL_OPTIONS
    spawn(paths.javaw, ['-jar', paths.jar], { cwd: workDir, stdio: 'ignore', windowsHide: true, env })
      .on('error', () => { /* 拉起失败静默降级：只影响被控能力，不影响 IM 主功能 */ })
  } catch { /* 建目录或启动失败都不阻塞主进程 */ }
}

// 单实例：防止双击两次图标起两个主进程、在回环探测窗口期内竞相拉起 Agent
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    const win = BrowserWindow.getAllWindows()[0]
    if (win) {
      if (win.isMinimized()) win.restore()
      win.focus()
    }
  })
}

app.whenReady().then(() => {
  setupDownload()
  setupVideoCompress()
  setupLocalDb()
  setupAgentAutostart()
  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
