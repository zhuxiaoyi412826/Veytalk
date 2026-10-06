'use strict'
const path = require('path')
const fs = require('fs')
const os = require('os')
const { spawn, execFile } = require('child_process')
const { screen } = require('electron')

/**
 * 桌面端直播推流桥（主进程）。
 *
 * 渲染进程经 preload 的 window.__IM_LIVE__ 调 im:live-start-push，这里 spawn 本机原生 ffmpeg
 * 采集「屏幕 / 摄像头」→ H.264 + AAC → 切成 HLS(fMP4) 小片写到本地临时目录；再用一个文件
 * 监听器把每个新分片与滚动更新的 index.m3u8 通过 HTTP PUT 上传到 Nginx 的 WebDAV 推流目录
 * （推流目录由后端下发的 pushUrl 去掉文件名得到）。观众端用 hls.js 从 Nginx 拉同一路 m3u8 播放。
 *
 * 为什么用「ffmpeg 落盘 + Node PUT」而不是让 ffmpeg 直接推：
 * ffmpeg 原生只会把 HLS 写到本地文件系统或经 HTTP POST 连续推流，都不能直接 PUT 成
 * 「一段一个对象」的静态文件；而 Nginx 侧只需开 dav_methods PUT 就能收，播放侧是纯静态
 * 拉取——控制面/媒体面彻底分离，Java 一个媒体字节都不碰（与项目直播方案一致）。
 *
 * 为什么在主进程：渲染进程 contextIsolation:true / nodeIntegration:false，起不了子进程；
 * 原生 ffmpeg 硬编（NVENC/QSV/AMF，缺驱动回落 libx264）流式读写磁盘，长直播也不涨内存。
 *
 * 浏览器 Web 部署没有 preload，前端拿不到 window.__IM_LIVE__，开播按钮据此禁用（仅供观看）。
 */

/** 当前推流会话；同一时刻只允许一路直播，单例足够 */
let active = null

/** 定位 ffmpeg：安装包 resources/ffmpeg > 开发期 bin > IM_FFMPEG_PATH > 系统 PATH（与视频压缩同一套优先级） */
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
  return 'ffmpeg'
}

/** 分辨率档位 → 高度像素。未知档位回落 720，避免拼出非法 scale 参数 */
function heightOf(resolution) {
  const m = String(resolution || '').match(/(\d{3,4})p?/i)
  const h = m ? parseInt(m[1], 10) : 720
  return [480, 720, 1080, 1440].includes(h) ? h : 720
}

/**
 * 探测硬件编码器（nvenc > qsv > amf），都没有回落 libx264。
 * 与视频压缩用的探测各自独立：直播要的是低延迟恒定码率参数，转码要的是恒定质量参数，
 * 两套 args 不同，缓存也分开，互不影响。
 */
function detectLiveEncoder(ffmpegPath, bitrateKbps) {
  const b = Math.max(300, bitrateKbps | 0 || 2500)
  const rate = ['-b:v', `${b}k`, '-maxrate', `${b}k`, '-bufsize', `${b * 2}k`]
  const soft = {
    name: 'libx264',
    hw: false,
    args: ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', ...rate]
  }
  return new Promise((resolve) => {
    let settled = false
    const done = (v) => { if (!settled) { settled = true; resolve(v) } }
    try {
      const proc = execFile(ffmpegPath, ['-hide_banner', '-encoders'], { timeout: 8000 }, (err, stdout) => {
        if (err) return done(soft)
        const out = String(stdout || '')
        // 旧式 preset：Maxwell(GTX900)/Pascal 老卡也吃（新式 p1-p7 部分老驱动不认，见项目踩坑记录）
        if (/\bh264_nvenc\b/.test(out)) {
          return done({ name: 'h264_nvenc', hw: true, args: ['-c:v', 'h264_nvenc', '-preset', 'medium', '-rc', 'vbr', ...rate] })
        }
        if (/\bh264_qsv\b/.test(out)) {
          return done({ name: 'h264_qsv', hw: true, args: ['-c:v', 'h264_qsv', ...rate] })
        }
        if (/\bh264_amf\b/.test(out)) {
          return done({ name: 'h264_amf', hw: true, args: ['-c:v', 'h264_amf', '-quality', 'balanced', ...rate] })
        }
        return done(soft)
      })
      proc.on('error', () => done(soft))
    } catch { done(soft) }
  })
}

/** 列出第一个 dshow 视频设备名（摄像头推流用）；列不到返回 null，由调用方报错 */
function detectCameraDevice(ffmpegPath) {
  return new Promise((resolve) => {
    let settled = false
    const done = (v) => { if (!settled) { settled = true; resolve(v) } }
    try {
      // -list_devices 会把设备清单打进 stderr，命令本身以非 0 退出属正常
      const proc = execFile(ffmpegPath, ['-hide_banner', '-list_devices', 'true', '-f', 'dshow', '-i', 'dummy'],
        { timeout: 8000 }, (err, stdout, stderr) => {
          const text = String(stderr || '') + String(stdout || '')
          // 视频设备段落里第一行形如：  "USB Camera" (video)
          const videoSection = text.split(/DirectShow video devices/i)[1] || text
          const m = videoSection.match(/"([^"]+)"\s*\(video\)/)
          done(m ? m[1] : null)
        })
      proc.on('error', () => done(null))
    } catch { done(null) }
  })
}

/** 采集输入参数：屏幕用 gdigrab 抓整个桌面，摄像头用 dshow（设备名来自 payload 或自动探测） */
async function buildInputArgs(ffmpegPath, sourceType, fps, deviceName, display) {
  if (sourceType === 'camera') {
    const name = deviceName || (await detectCameraDevice(ffmpegPath))
    if (!name) {
      throw new Error('未检测到摄像头设备')
    }
    return ['-f', 'dshow', '-framerate', String(fps), '-i', `video=${name}`]
  }
  // 默认屏幕分享：gdigrab 抓整个虚拟桌面；多屏且用户选了某屏时，用 offset/size 限定该屏区域，
  // 否则多显示器会抓到所有屏拼接的画面（含黑边/错位）。bounds 为 DIP 坐标，100% 缩放下与物理像素一致。
  const args = ['-f', 'gdigrab', '-framerate', String(fps)]
  if (display && display.bounds) {
    const b = display.bounds
    args.push('-offset_x', String(b.x), '-offset_y', String(b.y), '-video_size', `${b.width}x${b.height}`)
  }
  args.push('-i', 'desktop')
  return args
}

/**
 * 把本地文件 PUT 到 Nginx WebDAV。失败重试一次；仍失败只记日志不中断推流——
 * 观众端 hls.js 会自动重试拉取，偶发一个分片上传失败最多让那几秒卡顿，不值得为此停播。
 */
async function putFile(targetUrl, filePath, contentType) {
  let data
  try {
    data = fs.readFileSync(filePath)
  } catch {
    return false // 文件可能已被 ffmpeg 的 delete_segments 清掉，跳过
  }
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await fetch(targetUrl, {
        method: 'PUT',
        headers: contentType ? { 'Content-Type': contentType } : undefined,
        body: data
      })
      if (res.ok || res.status === 201 || res.status === 204) {
        return true
      }
    } catch { /* 网络抖动，重试 */ }
  }
  return false
}

/** 由 pushUrl（.../{roomId}/{streamKey}/index.m3u8）推出上传目录基址 */
function dirBaseOf(pushUrl) {
  const idx = String(pushUrl).lastIndexOf('/')
  return idx > 0 ? String(pushUrl).slice(0, idx) : String(pushUrl)
}

/** 依扩展名给出 Content-Type，与 Nginx 播放侧的 mime 配置对应 */
function contentTypeOf(name) {
  if (name.endsWith('.m3u8')) return 'application/vnd.apple.mpegurl'
  if (name.endsWith('.m4s')) return 'video/iso.segment'
  if (name.endsWith('.mp4')) return 'video/mp4'
  if (name.endsWith('.ts')) return 'video/mp2t'
  return undefined
}

/**
 * 建立本地目录 → Nginx 的上传泵。
 *
 * fs.watch 事件不保证「文件已写完」，所以 ffmpeg 侧统一开 +temp_file：分片与 m3u8 都先写
 * .tmp 再 rename 成正式名，我们看到正式名即内容完整。用一个待传集合 + 串行泵避免并发上传
 * 打乱顺序；m3u8 放最后传（它引用的分片要先就位），Set 天然去重同一文件的连续变更。
 */
function startUploader(dir, baseDir, onFirstUpload, onUploadError) {
  const pending = new Set()
  let pumping = false
  let closed = false
  let firstDone = false
  let initSent = false

  async function pump() {
    if (pumping || closed) return
    pumping = true
    try {
      while (!closed) {
        // init.mp4 是 fMP4 的初始化段（m3u8 用 #EXT-X-MAP 引用），缺了它观众端解不了码、一直转圈。
        // 它由 ffmpeg 一次性写出，Windows 的 fs.watch 偶发漏掉这种小文件的 create 事件，
        // 故每轮 pump 都主动探测本地 init.mp4，存在且未传就补进队列，不依赖 watch。
        if (!initSent && fs.existsSync(path.join(dir, 'init.mp4'))) pending.add('init.mp4')
        if (!pending.size) break
        // 优先 init.mp4，其次分片，最后 m3u8（m3u8 引用的内容要先就位）
        let name = null
        if (pending.has('init.mp4')) name = 'init.mp4'
        else {
          for (const n of pending) {
            if (n !== 'index.m3u8') { name = n; break }
          }
        }
        if (!name) name = [...pending][0]
        pending.delete(name)
        const ok = await putFile(`${baseDir}/${name}`, path.join(dir, name), contentTypeOf(name))
        if (ok) {
          if (name === 'init.mp4') initSent = true
          if (!firstDone) {
            firstDone = true
            if (onFirstUpload) onFirstUpload()
          }
        }
        if (!ok && onUploadError) onUploadError(name)
      }
    } finally {
      pumping = false
    }
  }

  const watcher = fs.watch(dir, (_eventType, filename) => {
    if (closed || !filename) return
    const name = String(filename)
    if (name.endsWith('.tmp') || name.startsWith('.')) return
    if (!/\.(m3u8|m4s|mp4|ts)$/i.test(name)) return
    pending.add(name)
    pump()
  })

  // 启动即探测一次：若 ffmpeg 在 watch 建立前已写出 init.mp4，这里补传
  pump()

  return {
    close() {
      closed = true
      pending.clear()
      try { watcher.close() } catch { /* 已关 */ }
    }
  }
}

/** 停止推流并清理临时目录。reason 仅用于日志/事件 */
function closeLivePush(reason) {
  if (!active) return
  const { proc, uploader, dir, send } = active
  active = null
  try { if (uploader) uploader.close() } catch { /* 忽略 */ }
  try {
    if (proc && !proc.killed) {
      // Windows 下 ffmpeg 收不到 SIGTERM，直接 kill；它无子进程，kill 即可终结
      proc.kill()
    }
  } catch { /* 忽略 */ }
  // 临时目录异步删，删不掉（文件句柄未释放）也不阻塞；下次开播用新目录
  if (dir) {
    setTimeout(() => { try { fs.rmSync(dir, { recursive: true, force: true }) } catch { /* 忽略 */ } }, 500)
  }
  if (send) {
    try { send('im:live-state', { state: 'stopped', reason: reason || 'manual' }) } catch { /* 窗口已关 */ }
  }
}

/**
 * 注册直播推流 IPC。
 * @param {import('electron').IpcMain} ipcMain
 * @param {(channel:string, payload:any)=>void} send 向渲染进程推事件
 */
function setupLiveBridge(ipcMain, send) {
  const wrap = (fn) => async (event, payload) => {
    try {
      return { ok: true, data: await fn(payload, event) }
    } catch (error) {
      console.warn('[live] IPC 执行失败:', error && error.message)
      return { ok: false, error: String((error && error.message) || error) }
    }
  }

  // 枚举显示器供多屏选择：屏幕分享默认抓整个虚拟桌面，多显示器时会拼屏，
  // 前端据此在开播面板让用户选某一块屏，主进程用其 bounds 限定 gdigrab 抓取区域。
  ipcMain.handle('im:live-list-displays', wrap(async () => {
    const primary = screen.getPrimaryDisplay()
    return screen.getAllDisplays().map((d, i) => ({
      id: d.id,
      label: `显示器 ${i + 1}（${d.bounds.width}x${d.bounds.height}${d.id === primary.id ? '，主屏' : ''}）`,
      bounds: d.bounds,
      primary: d.id === primary.id
    }))
  }))

  ipcMain.handle('im:live-start-push', wrap(async (payload) => {
    const { pushUrl, sourceType = 'screen', resolution = '720p', bitrateKbps = 2500, deviceName = '', displayId = null } = payload || {}
    if (!pushUrl) throw new Error('缺少推流地址')
    if (active) throw new Error('已有推流在进行中')

    // 渲染端只传 displayId（数字）——display 对象在主进程按 id 现查，避免把 Vue 响应式 Proxy
    // 传过 IPC 触发「An object could not be cloned」。查不到（id 失效/单屏未选）则回落整个虚拟桌面。
    const display = displayId == null ? null : (screen.getAllDisplays().find((d) => d.id === displayId) || null)

    const ffmpegPath = resolveFfmpegPath()
    const height = heightOf(resolution)
    // 屏幕分享 15fps 足够且省带宽/CPU，摄像头 30fps 更顺滑
    const fps = sourceType === 'camera' ? 30 : 15
    const gop = fps * 2
    const enc = await detectLiveEncoder(ffmpegPath, bitrateKbps)
    const inputArgs = await buildInputArgs(ffmpegPath, sourceType, fps, deviceName, display)

    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'im-live-'))
    const baseDir = dirBaseOf(pushUrl)
    const playlist = path.join(dir, 'index.m3u8')

    const args = [
      '-hide_banner', '-loglevel', 'warning',
      ...inputArgs,
      '-vf', `scale=-2:${height}`, '-r', String(fps),
      ...enc.args,
      '-pix_fmt', 'yuv420p', '-g', String(gop), '-keyint_min', String(fps), '-sc_threshold', '0',
      '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-ar', '44100',
      '-f', 'hls',
      '-hls_time', '2',
      '-hls_segment_type', 'fmp4',
      '-hls_fmp4_init_filename', 'init.mp4',
      '-hls_segment_filename', path.join(dir, 'seg%d.m4s'),
      '-hls_flags', 'independent_segments+delete_segments+omit_endlist+temp_file',
      '-hls_list_size', '6',
      playlist
    ]

    let proc
    try {
      // cwd 必须设为临时目录：-hls_fmp4_init_filename 给的是相对名 init.mp4，ffmpeg 会把它
      // 写到进程 cwd；不设 cwd 时 init.mp4 落到 Electron 工作目录而非 dir，上传泵在 dir 里
      // 永远找不到它，观众端缺 fMP4 初始化段解不了码（黑屏/转圈）。设 cwd=dir 后 init.mp4
      // 正确落进 dir，且 m3u8 里 EXT-X-MAP 仍是相对引用 init.mp4（已实验验证）。
      proc = spawn(ffmpegPath, args, { windowsHide: true, cwd: dir })
    } catch (e) {
      fs.rmSync(dir, { recursive: true, force: true })
      throw new Error(`无法启动 ffmpeg：${(e && e.message) || e}`)
    }

    const session = { proc, uploader: null, dir, send }
    active = session

    let stderrTail = ''
    let retriedSoft = false
    proc.stderr.on('data', (buf) => { stderrTail = (stderrTail + buf.toString()).slice(-2000) })

    const uploader = startUploader(
      dir,
      baseDir,
      () => { try { send('im:live-state', { state: 'pushing', encoder: enc.name }) } catch { /* 窗口已关 */ } },
      (name) => { try { send('im:live-state', { state: 'pushing', warn: `分片上传失败：${name}` }) } catch { /* 忽略 */ } }
    )
    session.uploader = uploader

    try { send('im:live-state', { state: 'starting', encoder: enc.name }) } catch { /* 忽略 */ }

    proc.on('error', (e) => {
      if (active === session) {
        try { send('im:live-state', { state: 'error', error: `ffmpeg 启动失败：${(e && e.message) || e}` }) } catch { /* 忽略 */ }
        closeLivePush('spawn-error')
      }
    })

    // 「意外退出」的收尾在后台跑，不能 await 它——否则 startPush 这个 invoke 要挂到直播结束才返回，
    // 前端 await 会一直卡住。这里只挂监听，handler 立刻返回「已启动」。
    // ffmpeg 非 0 退出（硬编不可用等）时：若是硬编且还没回落过，自动换 libx264 重开一次；否则上报 error 并清理。
    proc.on('close', (code) => {
      if (active !== session) return // 已被 closeLivePush 主动收掉
      if (code === 0) {
        closeLivePush('ffmpeg-exit')
        return
      }
      if (enc.hw && !retriedSoft) {
        retriedSoft = true
        // 硬编运行期失败（编译进来但本机无对应驱动）：回落软编重开
        try { send('im:live-state', { state: 'starting', encoder: 'libx264', note: '硬件编码失败，回落软件编码' }) } catch { /* 忽略 */ }
        active = null
        try { uploader.close() } catch { /* 忽略 */ }
        const b = Math.max(300, bitrateKbps | 0 || 2500)
        const softEnc = ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency',
          '-b:v', `${b}k`, '-maxrate', `${b}k`, '-bufsize', `${b * 2}k`]
        // 整条命令用软编参数重建（而非在原 args 里定位替换 -c:v 段，那样易碎）
        const rebuilt = [
          '-hide_banner', '-loglevel', 'warning',
          ...inputArgs,
          '-vf', `scale=-2:${height}`, '-r', String(fps),
          ...softEnc,
          '-pix_fmt', 'yuv420p', '-g', String(gop), '-keyint_min', String(fps), '-sc_threshold', '0',
          '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-ar', '44100',
          '-f', 'hls', '-hls_time', '2', '-hls_segment_type', 'fmp4',
          '-hls_fmp4_init_filename', 'init.mp4',
          '-hls_segment_filename', path.join(dir, 'seg%d.m4s'),
          '-hls_flags', 'independent_segments+delete_segments+omit_endlist+temp_file',
          '-hls_list_size', '6',
          playlist
        ]
        let retryProc
        try {
          retryProc = spawn(ffmpegPath, rebuilt, { windowsHide: true })
        } catch {
          closeLivePush('soft-retry-spawn-error')
          return
        }
        const retrySession = { proc: retryProc, uploader: null, dir, send }
        active = retrySession
        retryProc.stderr.on('data', (buf) => { stderrTail = (stderrTail + buf.toString()).slice(-2000) })
        retrySession.uploader = startUploader(
          dir, baseDir,
          () => { try { send('im:live-state', { state: 'pushing', encoder: 'libx264' }) } catch { /* 忽略 */ } },
          () => { /* 分片失败已在泵内处理 */ }
        )
        retryProc.on('close', (rc) => {
          if (active === retrySession) {
            if (rc !== 0) {
              try { send('im:live-state', { state: 'error', error: `推流中断（code=${rc}）：${stderrTail.slice(-300)}` }) } catch { /* 忽略 */ }
            }
            closeLivePush('ffmpeg-exit')
          }
        })
        retryProc.on('error', (e) => {
          if (active === retrySession) {
            try { send('im:live-state', { state: 'error', error: `ffmpeg 启动失败：${(e && e.message) || e}` }) } catch { /* 忽略 */ }
            closeLivePush('spawn-error')
          }
        })
        return
      }
      try { send('im:live-state', { state: 'error', error: `推流中断（code=${code}）：${stderrTail.slice(-300)}` }) } catch { /* 忽略 */ }
      closeLivePush('ffmpeg-exit')
    })

    // spawn 成功即认为已启动，立刻返回；后续状态变化经 'im:live-state' 事件推给渲染进程
    return { started: true, dir, encoder: enc.name }
  }))

  ipcMain.handle('im:live-stop-push', wrap(async () => {
    closeLivePush('manual')
    return { stopped: true }
  }))

  ipcMain.handle('im:live-query-state', wrap(() => (active ? { pushing: true } : { pushing: false })))

  const { app } = require('electron')
  app.on('before-quit', () => closeLivePush('app-quit'))
}

module.exports = { setupLiveBridge, closeLivePush }
