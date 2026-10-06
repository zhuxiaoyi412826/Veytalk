'use strict'
/**
 * 本机直播流媒体服务器（Nginx 的局域网自测替身）。
 *
 * 直播架构里控制面与媒体面彻底分离：Java 后端只管房间状态、播放地址签名、弹幕 WS，
 * 一个媒体字节都不碰；真正收发流媒体的是一个独立的 HTTP 文件服务器（生产用 Nginx，
 * 见 md/直播.md §10）。本机自测没有 Nginx 时，这个零依赖脚本顶上：
 *   - PUT /hls/{roomId}/{streamKey}/{file} → 落盘 live-data/hls/…（先写 .part 再原子 rename）
 *   - GET /hls/{roomId}/{streamKey}/{file} → 静态返回（带 CORS、Range，m3u8 不缓存）
 *
 * 与 Nginx 方案的差异：局域网自测**不校验签名**——后端下发的 ?expire=&sign= 被忽略，
 * 防盗链仍由路径里不可猜的 streamKey 兜底（每场随机、关播作废）。上生产请换回 §10 的 Nginx。
 *
 * 用法：node electron/live-media-server.js     （可选 PORT=xxxx 覆盖端口，默认 8088）
 * 配套：im.live.push-base-url=http://127.0.0.1:8088/hls（本机推流，与 DHCP 换 IP 无关）；
 *       play-base-url=/hls（相对路径，观众经前端同源代理拉流，IP 免配，见 md/直播.md §11）
 */
const http = require('http')
const fs = require('fs')
const path = require('path')

const PORT = Number(process.env.PORT || 8088)
const ROOT = path.join(__dirname, 'live-data') // URI /hls/… → ROOT/hls/…（与 Nginx root /data 同构）
const URL_PREFIX = '/hls/'

// 与 electron/live.js 的 contentTypeOf、Nginx 播放侧 types 保持一致
const MIME = {
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.m4s': 'video/iso.segment',
  '.mp4': 'video/mp4',
  '.ts': 'video/mp2t'
}

function contentTypeOf(file) {
  return MIME[path.extname(file).toLowerCase()] || 'application/octet-stream'
}

// 观众端（hls.js / WebView）与播放口不同源，CORS 头必给；add_header 不跨层继承的道理在这里也一样
function setCors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, PUT, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range')
  res.setHeader('Access-Control-Expose-Headers', 'Content-Length, Content-Range')
}

/** 把 URL path 安全映射到磁盘绝对路径；越界（../ 目录穿越）返回 null */
function resolveSafe(pathname) {
  const rel = pathname.replace(/^\/+/, '')
  const abs = path.resolve(ROOT, rel)
  const rootAbs = path.resolve(ROOT)
  if (abs !== rootAbs && !abs.startsWith(rootAbs + path.sep)) return null
  return abs
}

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end(body)
}

const server = http.createServer((req, res) => {
  setCors(res)
  // 只取 pathname：播放地址带的 ?expire=&sign= 在自测下被忽略（不校验签名）
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  const pathname = url.pathname

  if (!pathname.startsWith(URL_PREFIX)) return send(res, 404, 'not found')
  const abs = resolveSafe(pathname)
  if (!abs) return send(res, 403, 'forbidden')

  // 预检
  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    return res.end()
  }

  // 推流口：PUT 落盘。先写 {file}.part 收完再 rename，保证 GET 永远看到完整文件或 404，不会读到半截
  if (req.method === 'PUT') {
    const part = abs + '.part'
    fs.mkdirSync(path.dirname(abs), { recursive: true })
    const ws = fs.createWriteStream(part)
    req.pipe(ws)
    ws.on('finish', () => {
      fs.rename(part, abs, (err) => {
        if (err) {
          console.warn('[media] rename 失败:', err.message)
          return send(res, 500, 'write failed')
        }
        console.log('[media] PUT', pathname)
        res.writeHead(201) // electron/live.js 判 ok||201||204 为成功
        res.end()
      })
    })
    ws.on('error', (err) => {
      console.warn('[media] 写盘失败:', err.message)
      send(res, 500, 'write failed')
    })
    return
  }

  // 播放口：GET/HEAD 静态返回，支持单段 Range（MSE/部分播放器会发）
  if (req.method === 'GET' || req.method === 'HEAD') {
    fs.stat(abs, (err, stat) => {
      if (err || !stat.isFile()) return send(res, 404, 'not found')
      const type = contentTypeOf(abs)
      // m3u8 滚动更新绝不缓存；分片不可变可长缓存
      res.setHeader('Cache-Control', abs.endsWith('.m3u8') ? 'no-store, must-revalidate' : 'public, max-age=3600')
      const range = req.headers.range
      if (range) {
        const m = /bytes=(\d*)-(\d*)/.exec(range)
        let start = m && m[1] ? parseInt(m[1], 10) : 0
        let end = m && m[2] ? parseInt(m[2], 10) : stat.size - 1
        if (isNaN(start) || start < 0) start = 0
        if (isNaN(end) || end >= stat.size) end = stat.size - 1
        if (start > end) {
          res.writeHead(416, { 'Content-Range': `bytes */${stat.size}` })
          return res.end()
        }
        res.writeHead(206, {
          'Content-Type': type,
          'Content-Range': `bytes ${start}-${end}/${stat.size}`,
          'Accept-Ranges': 'bytes',
          'Content-Length': end - start + 1
        })
        if (req.method === 'HEAD') return res.end()
        fs.createReadStream(abs, { start, end }).pipe(res)
        return
      }
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': stat.size, 'Accept-Ranges': 'bytes' })
      if (req.method === 'HEAD') return res.end()
      fs.createReadStream(abs).pipe(res)
    })
    return
  }

  return send(res, 405, 'method not allowed')
})

// 监听 0.0.0.0：局域网里的手机/别的电脑才连得进来（只听 localhost 就只能本机看）
server.listen(PORT, '0.0.0.0', () => {
  console.log('[media] 直播流媒体服务器已启动（Nginx 局域网自测替身）')
  console.log(`[media] 监听 http://0.0.0.0:${PORT}   落盘根目录 ${ROOT}`)
  console.log(`[media] 后端 im.live：push-base-url=http://127.0.0.1:${PORT}/hls、play-base-url=/hls（前端同源，IP 免配）`)
  console.log('[media] 仅局域网自测：不校验签名、无 HTTPS；生产请改用 md/直播.md §10 的 Nginx')
})
