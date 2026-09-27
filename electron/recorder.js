'use strict'
const path = require('path')
const fs = require('fs')
const { app, ipcMain, dialog, shell, BrowserWindow } = require('electron')

/**
 * 主进程录屏落盘：渲染进程把 MediaRecorder 切出的分片流式送来，这里只管目录、文件句柄与元数据。
 *
 * 为什么不在渲染进程录完再整块保存：一次远控会话动辄几十分钟，产物全攒在内存 Blob 里会一路
 * 涨到几百 MB，窗口一崩就什么都留不下。改成「每个分片到达即 append 到磁盘」后，常驻内存只有
 * 一个分片的大小，进程被强杀时文件里也已经留有到上一秒为止的画面。
 *
 * 为什么用 writeSync 而不是 createWriteStream：分片按 timeslice 每秒才一两个、总量几百 KB，
 * 同步写的开销可以忽略；好处是「调用返回即数据已交给操作系统」，不必维护 stream 的背压和
 * error 事件，也不会在退出时因为流尚未 flush 而丢掉尾巴。
 *
 * 浏览器 Web 部署没有 preload，前端拿不到 window.__IM_NATIVE__.record，本功能整体自动关闭。
 */

/** 当前正在录制的文件：同一时刻只会有一路远控会话，单例足够 */
let active = null

/** 默认录制目录：系统「视频」下的 IM远程录屏，用户可在设置里改成任意目录 */
function defaultDir() {
  const base = app.getPath('videos') || app.getPath('userData')
  return path.join(base, 'IM远程录屏')
}

/** 抹掉 Windows 文件名非法字符并截断：设备名来自对端上报，不能原样拼进路径 */
function safeName(name, fallback) {
  const cleaned = String(name || '')
    .replace(/[\\/:*?"<>|\r\n\t]/g, '_')
    .trim()
    .slice(0, 40)
  return cleaned || fallback
}

/** 20260927-153012：文件名按字典序排即按会话先后排，翻目录时不用看属性 */
function timestamp(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}` +
    `-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`
  )
}

/** 同名文件已存在时追加 -2/-3：同一秒内重开会话（断线重连）会撞名，不能覆盖掉上一段录像 */
function uniquePath(dir, base, ext) {
  let candidate = path.join(dir, `${base}.${ext}`)
  for (let i = 2; fs.existsSync(candidate); i++) {
    candidate = path.join(dir, `${base}-${i}.${ext}`)
  }
  return candidate
}

/** sidecar 与视频同名不同扩展名，播放器不认识 .json 也不会误把它当媒体列出 */
function sidecarOf(filePath) {
  return filePath.replace(/\.[^.]+$/, '') + '.json'
}

/** 写审计元数据：失败只记警告，不能因为写不了 json 就把已经录好的视频判为失败 */
function writeSidecar(filePath, payload) {
  try {
    fs.writeFileSync(sidecarOf(filePath), JSON.stringify(payload, null, 2), 'utf8')
    return sidecarOf(filePath)
  } catch (error) {
    console.warn('[recorder] 审计元数据写入失败:', error && error.message)
    return null
  }
}

/** 关闭当前录制并返回落盘结果。meta/auditEvents 为空表示异常收尾（如退出应用）。 */
function closeActive(meta, auditEvents, reason) {
  if (!active) {
    return null
  }
  const { fd, filePath, startedAt } = active
  active = null
  try {
    fs.closeSync(fd)
  } catch (error) {
    console.warn('[recorder] 关闭录制文件失败:', error && error.message)
  }

  let size = 0
  try {
    size = fs.statSync(filePath).size
  } catch {
    /* 文件被外部删掉：size 保持 0，仍按失败之外的正常流程返回 */
  }
  const durationMs = Date.now() - startedAt

  // 正常收尾由渲染进程带上会话信息与操作审计；异常收尾（进程退出）只补一条中断说明，
  // 免得目录里出现一个没有任何元数据、无法追溯来源的录像文件
  const sidecar = writeSidecar(
    filePath,
    meta
      ? {
          type: 'im-remote-screen-recording',
          version: 1,
          video: path.basename(filePath),
          videoBytes: size,
          durationMs,
          startedAt: new Date(startedAt).toISOString(),
          endedAt: new Date().toISOString(),
          session: meta,
          auditEvents: Array.isArray(auditEvents) ? auditEvents : []
        }
      : {
          type: 'im-remote-screen-recording',
          version: 1,
          video: path.basename(filePath),
          videoBytes: size,
          durationMs,
          startedAt: new Date(startedAt).toISOString(),
          aborted: reason || 'app-exit',
          note: '录制未走正常收尾流程（应用退出或窗口关闭），本文件由主进程自动封口'
        }
  )

  return { filePath, size, durationMs, sidecar }
}

/**
 * 应用退出 / 所有窗口关闭时调用：把还开着的录制文件收口。
 * 不调的话 fd 一直挂着，最后一段分片可能没落盘，文件也删不掉。
 */
function closeRecording(reason) {
  closeActive(null, null, reason)
}

/** 注册录屏 IPC。渲染进程桥在 preload.js 的 __IM_NATIVE__.record。 */
function setupRecorder() {
  // 与 localdb 同一套信封：handler 统一返回 { ok, data|error }，异常直抛的话
  // preload 侧只会看到「Error invoking remote method」，排查时拿不到根因
  const wrap = (fn) => async (event, payload) => {
    try {
      return { ok: true, data: await fn(payload, event) }
    } catch (error) {
      console.warn('[recorder] IPC 执行失败:', error && error.message)
      return { ok: false, error: String((error && error.message) || error) }
    }
  }

  ipcMain.handle('im:record-default-dir', wrap(() => defaultDir()))

  ipcMain.handle(
    'im:record-pick-dir',
    wrap(async (payload, event) => {
      const options = {
        title: '选择录屏保存目录',
        defaultPath: (payload && payload.current) || defaultDir(),
        buttonLabel: '选择此目录',
        properties: ['openDirectory', 'createDirectory']
      }
      const win = BrowserWindow.fromWebContents(event.sender)
      const result = win ? await dialog.showOpenDialog(win, options) : await dialog.showOpenDialog(options)
      // 用户点取消：返回 null，前端保持原值不动
      return result.canceled || !result.filePaths || !result.filePaths.length ? null : result.filePaths[0]
    })
  )

  ipcMain.handle(
    'im:record-start',
    wrap((payload) => {
      const { dir, ext, meta } = payload || {}
      const target = dir ? String(dir) : defaultDir()
      if (!path.isAbsolute(target)) {
        throw new Error('录制目录必须是绝对路径')
      }
      fs.mkdirSync(target, { recursive: true })
      // 探一次写权限：目录只读（如放在 C:\Program Files 下）时当场报错，
      // 总比录了一整场会话到收尾才发现全丢要好
      fs.accessSync(target, fs.constants.W_OK)

      // 上一路没收干净（渲染进程异常）就先封口，避免两个 fd 同时写
      closeActive(null, null, 'superseded')

      const extension = safeName(ext, 'webm').replace(/[^a-zA-Z0-9]/g, '').slice(0, 5) || 'webm'
      const base = `远控录屏_${safeName(meta && meta.deviceName, '设备')}_${timestamp()}`
      const filePath = uniquePath(target, base, extension)
      active = { fd: fs.openSync(filePath, 'w'), filePath, base, startedAt: Date.now(), bytes: 0 }
      return { filePath, baseName: base }
    })
  )

  ipcMain.handle(
    'im:record-chunk',
    wrap((payload) => {
      if (!active) {
        throw new Error('没有进行中的录制')
      }
      const data = payload && payload.data
      if (!data) {
        return active.bytes
      }
      // ArrayBuffer / Uint8Array / Buffer 都能直接喂给 Buffer.from；ArrayBuffer 是零拷贝建视图
      const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data)
      fs.writeSync(active.fd, buffer, 0, buffer.length)
      active.bytes += buffer.length
      return active.bytes
    })
  )

  ipcMain.handle(
    'im:record-stop',
    wrap((payload) => {
      const { meta, auditEvents, reason } = payload || {}
      return closeActive(meta, auditEvents, reason)
    })
  )

  ipcMain.handle('im:record-state', wrap(() => (active ? { filePath: active.filePath, bytes: active.bytes } : null)))

  ipcMain.handle(
    'im:record-open-dir',
    wrap((payload) => {
      const target = payload && payload.filePath
      if (target && fs.existsSync(target)) {
        // 直接选中该文件，比只打开目录少一步找文件
        shell.showItemInFolder(target)
        return true
      }
      const dir = (payload && payload.dir) || defaultDir()
      fs.mkdirSync(dir, { recursive: true })
      shell.openPath(dir)
      return true
    })
  )

  app.on('before-quit', () => {
    closeRecording('app-quit')
  })
}

module.exports = { setupRecorder, closeRecording, defaultDir }
