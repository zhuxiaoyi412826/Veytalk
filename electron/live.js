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
 * 采集「屏幕 / 摄像头」→ 一份源经 split 同时编成 <b>多档</b> H.264 + AAC → 切成 HLS(fMP4) 小片
 * 写到本地临时目录；再用一个文件监听器把每个新分片、每一档的清单与汇总的主清单 index.m3u8
 * 通过 HTTP PUT 上传到 Nginx 的 WebDAV 推流目录（推流目录由后端下发的 pushUrl 去掉文件名得到）。
 * 观众端用 hls.js 从 Nginx 拉同一路 index.m3u8，拿到主清单就能在 2K/1K/720P/480P 之间切档。
 *
 * 为什么用「ffmpeg 落盘 + Node PUT」而不是让 ffmpeg 直接推：
 * ffmpeg 原生只会把 HLS 写到本地文件系统或经 HTTP POST 连续推流，都不能直接 PUT 成
 * 「一段一个对象」的静态文件；而 Nginx 侧只需开 dav_methods PUT 就能收，播放侧是纯静态
 * 拉取——控制面/媒体面彻底分离，Java 一个媒体字节都不碰（与项目直播方案一致）。
 *
 * 为什么在主进程：渲染进程 contextIsolation:true / nodeIntegration:false，起不了子进程；
 * 原生 ffmpeg 硬编（NVENC/QSV/AMF，缺驱动回落 libx264）流式读写磁盘，长直播也不涨内存。
 *
 * 为什么阶梯默认开启、不给开关：本机实测四档阶梯（2K/1K/720P/480P）libx264 veryfast 15fps
 * 编码速度 4.20× 实时、NVENC 3.61× 实时，普通机器也追得上采集；且主播只需在原画里选一个
 * 分辨率，多档是派生物而非额外配置项（详见 {@link LADDER_TIERS}）。
 *
 * 回放（关播时顺手得到，不额外录制一遍）：媒体服务器对分片只增不删，所以整场画面本来就在
 * 盘上；关播时按档位把上传台账逐档定版成 VOD 清单，PUT 到不带 streamKey 的
 * {roomId}/replay/{档}.m3u8，再拼一份主清单 PUT 到 {roomId}/replay/index.m3u8，分片用相对 URI
 * ../{streamKey}/seg_{档}_{n}.m4s 引回。细节与已知代价见 md/直播.md §15。
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
 * 清晰度阶梯表：主播只选「原画」，其余档位由原画<b>向下派生</b>，观众端在同一场里切档。
 *
 * <p>为什么必须真的多档而不前端缩放：HLS 的多档（master playlist + variants）才是
 * 「观众按自己带宽选一路码流」；把 720p 拉到 2K 显示只是放大像素，看不清还是看不清。
 *
 * <p>ratio 是相对原画码率的系数（原画 = 主播设的 bitrateKbps）。本机实测四档阶梯
 * （2K/1K/720P/480P，libx264 veryfast 15fps）编码速度 4.20× 实时、NVENC 3.61× 实时，
 * 即普通机器同时压四档也追得上采集，所以阶梯默认开启、不给用户加开关。
 */
const LADDER_TIERS = [
  { name: '2k', height: 1440, ratio: 1 },
  { name: '1k', height: 1080, ratio: 0.55 },
  { name: '720p', height: 720, ratio: 0.3 },
  { name: '480p', height: 480, ratio: 0.16 }
]

/** 档位名 → 展示用的清晰度标签（与观众端 im-ui/src/utils/quality.js 同一口径：一律用 p 记法，
 *  不另起 1K/2K 这类叫法——主播面板里的「档位 …」和观众选择器里的选项必须对得上同一个东西） */
function qualityLabel(height) {
  if (height >= 1440) return '1440p'
  if (height >= 1080) return '1080p'
  if (height >= 720) return '720p'
  if (height >= 480) return '480p'
  return `${height}p`
}

/**
 * 按原画向下派生阶梯：2K 出四档、1080p 出三档、720p 出两档、480p 只有一档。
 *
 * @param capHeight 采集源真实高度（物理像素），高于它的档位直接砍掉——1080p 的屏硬放大到
 *                  2K 没有信息增量，白烧一路编码还更糊；传 0 表示未知（摄像头），不砍
 */
function buildLadder(height, bitrateKbps, capHeight) {
  const base = Math.max(300, bitrateKbps | 0 || 2500)
  const start = Math.max(0, LADDER_TIERS.findIndex((t) => t.height === height))
  const cap = Number(capHeight) > 0 ? Number(capHeight) : Infinity
  const picked = LADDER_TIERS.slice(start).filter((t) => t.height <= cap)
  const list = picked.length ? picked : [LADDER_TIERS[LADDER_TIERS.length - 1]]
  return list.map((t) => ({
    name: t.name,
    label: qualityLabel(t.height),
    height: t.height,
    // 每档下限 400k：再低就没有能看的画质了，宁可少出一档也别给观众一坨马赛克
    bitrateKbps: Math.max(400, Math.min(base, Math.round(base * t.ratio)))
  }))
}

/**
 * 采集源高度（物理像素）。屏幕选了某块屏就用它的 bounds，没选就是所有屏纵向并集（gdigrab 抓虚拟桌面）；
 * bounds 是 DIP，缩放 150% 的 4K 屏物理高度 2160 而 DIP 只有 1440，不乘 scaleFactor 会误砍掉 2K 档。
 * 摄像头返回 0：dshow 设备给什么分辨率不可预知，不砍档（保持原有的按主播所选直接 scale）。
 */
function sourceHeight(display) {
  const phys = (d) => Math.round((d.bounds?.height || 0) * (d.scaleFactor || 1))
  if (display) return phys(display)
  try {
    const ds = screen.getAllDisplays()
    if (!ds.length) return 0
    let top = Infinity
    let bottom = -Infinity
    for (const d of ds) {
      top = Math.min(top, d.bounds.y)
      bottom = Math.max(bottom, d.bounds.y + d.bounds.height)
    }
    const scale = ds[0].scaleFactor || 1
    return Math.round((bottom - top) * scale)
  } catch {
    return 0
  }
}

/** 阶梯里每一档在本地/服务器上对应的文件名（init、变体清单、分片前缀） */
const initNameOf = (tier) => `init_${tier.name}.mp4`
const playlistNameOf = (tier) => `index_${tier.name}.m3u8`
const MASTER_NAME = 'index.m3u8'

/**
 * 组装 ffmpeg 命令（多档 HLS 阶梯）。
 *
 * <p>为什么抽成函数：硬编运行期失败要用软编<b>整条重建</b>命令（在原 args 里定位替换 -c:v 段易碎），
 * 两处各写一遍字面量必然漂移，所以首次 spawn 与回落重开共用这一个构建器。
 *
 * <p>实测要点（本机 electron/bin/ffmpeg 7.1，结论不靠猜）：
 * <ul>
 *   <li>-var_stream_map 只认 {@code v:i,name:x}；手写 bandwidth=/resolution= 会报 Invalid keyval 退出 -22，
 *       主清单里的 BANDWIDTH/RESOLUTION/CODECS 由 ffmpeg 自己算，本来就更准；</li>
 *   <li>码率参数按输出流序号绑定（-b:v:0 对应 v:0 那一档）：高细节源下四档实测带宽
 *       7.29/3.29/1.69/0.90 Mbps 与 6000/3000/1500/800k 单调对应；</li>
 *   <li>输出清单名必须含 %v，否则各档会互相覆盖同一个文件；主清单靠 -master_pl_name 另起名字。</li>
 * </ul>
 */
function buildFfmpegArgs({ inputArgs, ladder, fps, gop, encArgs, dir }) {
  const n = ladder.length
  // 一路解码 → split 成 n 路各自 scale → n 路编码：比起 n 个 ffmpeg 省掉 n-1 份采集与解码开销
  let fc = `[0:v]split=${n}`
  for (let i = 0; i < n; i++) fc += `[s${i}]`
  for (let i = 0; i < n; i++) fc += `;[s${i}]scale=-2:${ladder[i].height}[v${i}]`
  const maps = []
  const rates = []
  ladder.forEach((t, i) => {
    maps.push('-map', `[v${i}]`)
    const kb = t.bitrateKbps
    rates.push('-b:v:' + i, `${kb}k`, '-maxrate:' + i, `${kb}k`, '-bufsize:' + i, `${kb * 2}k`)
  })
  return [
    '-hide_banner', '-loglevel', 'warning',
    ...inputArgs,
    '-filter_complex', fc,
    ...maps,
    '-r', String(fps),
    ...encArgs,
    ...rates,
    '-pix_fmt', 'yuv420p', '-g', String(gop), '-keyint_min', String(fps), '-sc_threshold', '0',
    '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-ar', '44100',
    '-f', 'hls',
    '-hls_time', '1',
    '-hls_segment_type', 'fmp4',
    // 单档时 %v 不展开（实测写出字面量文件名 init_%v.mp4），所以单档直接给字面名
    '-hls_fmp4_init_filename', n > 1 ? 'init_%v.mp4' : initNameOf(ladder[0]),
    '-hls_segment_filename', path.join(dir, 'seg_%v_%d.m4s'),
    '-hls_flags', 'independent_segments+delete_segments+omit_endlist+temp_file',
    // 12 片 × 1s = 12s 滑窗，与原 6 × 2s 窗口长度一致
    '-hls_list_size', '12',
    // 主清单沿用 index.m3u8 这个名字：后端 LiveUrlSigner 只签 {roomId}/{streamKey}/index.m3u8，
    // 名字不变就零后端改动（变体清单与分片本来就不签，见 md/直播.md §10.1）
    '-master_pl_name', MASTER_NAME,
    '-var_stream_map', ladder.map((t, i) => `v:${i},name:${t.name}`).join(' '),
    path.join(dir, 'index_%v.m3u8')
  ]
}

/**
 * 探测硬件编码器（nvenc > qsv > amf），都没有回落 libx264。
 * 与视频压缩用的探测各自独立：直播要的是低延迟恒定码率参数，转码要的是恒定质量参数，
 * 两套 args 不同，缓存也分开，互不影响。
 *
 * <p>只给「编码器本体」、不给码率：码率现在按阶梯逐档绑定（-b:v:0/1/2/3），
 * 写在探测器里就只有一个档位能用，见 {@link buildFfmpegArgs}。
 */
function detectLiveEncoder(ffmpegPath) {
  const soft = {
    name: 'libx264',
    hw: false,
    args: ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency']
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
          return done({ name: 'h264_nvenc', hw: true, args: ['-c:v', 'h264_nvenc', '-preset', 'medium', '-rc', 'vbr'] })
        }
        if (/\bh264_qsv\b/.test(out)) {
          return done({ name: 'h264_qsv', hw: true, args: ['-c:v', 'h264_qsv'] })
        }
        if (/\bh264_amf\b/.test(out)) {
          return done({ name: 'h264_amf', hw: true, args: ['-c:v', 'h264_amf', '-quality', 'balanced'] })
        }
        return done(soft)
      })
      proc.on('error', () => done(soft))
    } catch { done(soft) }
  })
}

/** 软编回落参数：与探测到的硬编同形（不带码率，码率由阶梯逐档注入） */
const SOFT_ENC_ARGS = ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency']

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

/**
 * 把上传基址拆成「房间目录」与「本场 streamKey」。
 * 回放清单落在 {@code {roomBase}/replay/index.m3u8}（不带 streamKey 的持久地址），
 * 而分片留在 {@code {roomBase}/{streamKey}/} 里，两者靠清单内的相对 URI 连起来。
 */
function splitBase(baseDir) {
  const s = String(baseDir)
  const idx = s.lastIndexOf('/')
  return idx < 0 ? { room: s, key: '' } : { room: s.slice(0, idx), key: s.slice(idx + 1) }
}

/**
 * ffmpeg 阶梯的分片命名（-hls_segment_filename 用的 seg_%v_%d.m4s），回放台账按它识别有效分片。
 * 单档时代是 seg%d.m4s，现在统一带档位名，所以档位名就是台账分组的 key。
 * 字符集限 [a-z0-9]（不用 \w）：\w 含下划线，会把 seg_2k_0 里的分隔符吃掉导致错分。
 */
const SEG_NAME_RE = /^seg_([a-z0-9]+)_(\d+)\.m4s$/i

/** 变体媒体清单（index_2k.m3u8 这种），区别于主清单 index.m3u8 */
const VARIANT_PL_RE = /^index_([a-z0-9]+)\.m3u8$/i

/**
 * 把一段文本 PUT 到目标地址。与 {@link putFile} 的区别是没有本地文件：
 * 回放清单是内存里拼出来的，为它落一个临时文件再删不划算。
 */
async function putText(targetUrl, text, contentType) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await fetch(targetUrl, {
        method: 'PUT',
        headers: contentType ? { 'Content-Type': contentType } : undefined,
        body: text
      })
      if (res.ok || res.status === 201 || res.status === 204) return true
    } catch { /* 网络抖动，重试 */ }
  }
  return false
}

/**
 * 拼一档的 VOD（整场可拖进度条）清单。
 *
 * <p>为什么能这么便宜地得到回放：媒体服务器（Nginx/WebDAV 或本机自测用的
 * live-media-server.js）对分片<b>只增不删</b>，ffmpeg 的 delete_segments 清的是主播本机的
 * 临时目录，服务器上的 {@code seg_2k_N.m4s} 整场都在。所以「回放」缺的从来不是画面字节，
 * 而是一份不滚动、带 ENDLIST 的清单——直播中那份变体清单是 12 秒滑窗且
 * {@code omit_endlist}，永远不能拿来回看。
 *
 * <p>分片写成相对 URI {@code ../{streamKey}/seg_2k_N.m4s}：清单自己不带 key（可长期下发、
 * 可重签），而能力凭证仍然藏在清单正文里——拿不到清单就拼不出分片路径。
 * 这与直播侧「只签 m3u8、分片不签」的口径一致（见 md/直播.md §10.1）。
 */
function buildVodPlaylist(tier, list, streamKey) {
  if (!streamKey || !list.length) return null
  let target = 1
  for (const s of list) target = Math.max(target, Math.ceil(s.seconds || 1))
  const prefix = `../${streamKey}/`
  const out = [
    '#EXTM3U',
    // EXT-X-MAP（fMP4 初始化段）是协议 7 才有的标签，版本写低了 hls.js 会忽略 MAP 而不解码
    '#EXT-X-VERSION:7',
    '#EXT-X-PLAYLIST-TYPE:VOD',
    '#EXT-X-INDEPENDENT-SEGMENTS',
    `#EXT-X-TARGETDURATION:${target}`,
    `#EXT-X-MEDIA-SEQUENCE:${list[0].seq}`,
    `#EXT-X-MAP:URI="${prefix}${initNameOf(tier)}"`
  ]
  let prev = null
  for (const s of list) {
    // 序号跳空说明中间那一片没传上去（网络抖动丢了 1 秒）。HLS 里时间戳不连续必须显式标记，
    // 否则播放器当成连续区间去算缓冲，表现为拖过去就卡死
    if (prev !== null && s.seq > prev + 1) out.push('#EXT-X-DISCONTINUITY')
    out.push(`#EXTINF:${Number(s.seconds || 1).toFixed(6)},`)
    out.push(`${prefix}seg_${tier.name}_${s.seq}.m4s`)
    prev = s.seq
  }
  out.push('#EXT-X-ENDLIST')
  return out.join('\n') + '\n'
}

/**
 * 拼回放的主清单（多档）：与直播侧同构，观众端 hls.js 拿到它就能枚举出档位列表。
 * 地址仍然是 {@code replay/index.m3u8}（后端只签这个名），档位清单平级放在
 * {@code replay/{name}.m3u8}。
 */
function buildReplayMaster(entries) {
  if (!entries.length) return null
  const out = ['#EXTM3U', '#EXT-X-VERSION:7', '#EXT-X-INDEPENDENT-SEGMENTS']
  for (const e of entries) {
    // BANDWIDTH 用本场台账算出的实测均值（比直播那份只统计滑窗内几秒的主清单更准）
    const attrs = [`BANDWIDTH=${e.bandwidth}`]
    if (e.resolution) attrs.push(`RESOLUTION=${e.resolution}`)
    if (e.codecs) attrs.push(`CODECS="${e.codecs}"`)
    out.push(`#EXT-X-STREAM-INF:${attrs.join(',')}`)
    out.push(`${e.name}.m3u8`)
  }
  return out.join('\n') + '\n'
}

/**
 * 从本地那份直播主清单里抽每一档的 RESOLUTION / CODECS，回放主清单直接沿用。
 * 宽度靠猜不准（scale=-2 按实际宽高比取偶数，非 16:9 的屏就对不上），ffmpeg 已经算好了就用它的。
 */
function readLiveMasterAttrs(dir) {
  const map = new Map()
  let text
  try {
    text = fs.readFileSync(path.join(dir, MASTER_NAME), 'utf8')
  } catch {
    return map // 一场都没写出（秒开秒关）：没有属性可用，下面走缺省分支
  }
  const lines = text.split(/\r?\n/)
  let pending = null
  for (const line of lines) {
    const m = /^#EXT-X-STREAM-INF:(.*)$/.exec(String(line).trim())
    if (m) { pending = m[1]; continue }
    const uri = String(line).trim()
    if (!uri || uri.startsWith('#') || !pending) continue
    const vm = VARIANT_PL_RE.exec(path.basename(uri))
    if (vm) {
      const res = /RESOLUTION=([\d x]+)/.exec(pending)
      const cod = /CODECS="([^"]+)"/.exec(pending)
      map.set(vm[1].toLowerCase(), {
        resolution: res ? res[1].replace(/\s+/g, '') : '',
        codecs: cod ? cod[1] : ''
      })
    }
    pending = null
  }
  return map
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
 * 建立本地目录 → Nginx 的上传泵（多档阶梯）。
 *
 * fs.watch 事件不保证「文件已写完」，所以 ffmpeg 侧统一开 +temp_file：分片与 m3u8 都先写
 * .tmp 再 rename 成正式名，我们看到正式名即内容完整。用一个待传集合 + 串行泵避免并发上传
 * 打乱顺序；Set 天然去重同一文件的连续变更。
 *
 * <p>一轮直播现在会写出四类文件：每一档的 init_{档}.mp4、每一档的 index_{档}.m3u8、
 * 分片 seg_{档}_{n}.m4s，以及汇总的主清单 index.m3u8。上传顺序必须是
 * 「init → 分片 → 变体清单 → 主清单」：主清单先于变体上去，观众拿到主清单去拉变体就 404。
 */
function startUploader(dir, baseDir, ladder, onFirstUpload, onUploadError) {
  const pending = new Set()
  let pumping = false
  let closed = false
  let firstDone = false
  /* 收尾阶段不再收新文件，但把已排队的传完（区别于 closed 的「直接作废」） */
  let stopping = false
  /* 排空完成的通知口：pump 因 pending 空而退出时叫醍 finalizeReplay */
  let drainWake = null

  const { room: roomBase, key: streamKey } = splitBase(baseDir)
  /** 文件名 -> 档位名，让 pump 能认出哪些是 init、哪些是变体清单 */
  const initFiles = new Map(ladder.map((t) => [initNameOf(t), t.name]))
  /**
   * 回放台账：分片文件名 -> {variant, seq, seconds, bytes}，只记<b>真正 PUT 成功</b>的那些。
   *
   * <p>为什么在内存里记而不问服务器：媒体服务器只有 PUT/GET，没有 PROPFIND 之类
   * 的列目录能力（有它也不能区分「传了一半」与「传完了」）。代价是主进程中途挂掉
   * 就丢了台账，那一场的回放出不来——可接受：分片本身还在盘上，不丢画面。
   */
  const segs = new Map()
  /** 已经把 init 传上去的档位：某一档的 init 没传成，它的回放清单就解不了码，不入主清单 */
  const initSent = new Set()

  /** 从本地滞动清单里抓每片的真实 EXTINF（时长台账只在这一刻可用，分片被 delete_segments 清掉后就没了） */
  function noteDurations(tierName) {
    const tier = ladder.find((t) => t.name === tierName)
    if (!tier) return
    let text
    try {
      // 读的是<b>变体</b>清单：阶梯形态下 index.m3u8 是主清单，里面根本没有 EXTINF
      text = fs.readFileSync(path.join(dir, playlistNameOf(tier)), 'utf8')
    } catch {
      return // 那一档的清单还没写出第一轮
    }
    const lines = text.split(/\r?\n/)
    let inf = null
    for (const line of lines) {
      const m = /^#EXTINF:([\d.]+)/.exec(line)
      if (m) { inf = parseFloat(m[1]); continue }
      const uri = String(line).trim()
      if (!uri || uri.startsWith('#') || inf == null) continue
      const sm = SEG_NAME_RE.exec(path.basename(uri))
      if (sm) {
        const name = sm[0]
        const rec = segs.get(name) || { variant: sm[1], seq: Number(sm[2]), seconds: 0, bytes: 0 }
        rec.seconds = Number.isFinite(inf) && inf > 0 ? inf : 1
        segs.set(name, rec)
      }
      inf = null
    }
  }

  /** 队列里挑下一个：init 优先（缺了它一路都解不了码），再分片，再变体清单，主清单最后 */
  function pickNext() {
    for (const [file, tierName] of initFiles) {
      if (pending.has(file) && !initSent.has(tierName)) {
        pending.delete(file)
        return file
      }
    }
    for (const n of pending) {
      if (n !== MASTER_NAME && !n.endsWith('.m3u8')) { pending.delete(n); return n }
    }
    for (const n of pending) {
      if (n !== MASTER_NAME) { pending.delete(n); return n }
    }
    if (pending.has(MASTER_NAME)) { pending.delete(MASTER_NAME); return MASTER_NAME }
    return null
  }

  async function pump() {
    if (pumping || closed) return
    pumping = true
    try {
      while (!closed) {
        // init_{档}.mp4 是 fMP4 的初始化段（清单用 #EXT-X-MAP 引用），缺了它观众端解不了码、一直转圈。
        // 它们由 ffmpeg 一次性写出，Windows 的 fs.watch 偶发漏掉这种小文件的 create 事件，
        // 故每轮 pump 都主动探测本地 init，存在且未传就补进队列，不依赖 watch。
        // 收尾阶段不再探测：关播时重写的 init 与已入库的分片不是同一轮产物，补传它反而会错配
        if (!stopping) {
          for (const [file, tierName] of initFiles) {
            if (!initSent.has(tierName) && fs.existsSync(path.join(dir, file))) pending.add(file)
          }
        }
        if (!pending.size) break
        const name = pickNext()
        if (!name) break
        const ok = await putFile(`${baseDir}/${name}`, path.join(dir, name), contentTypeOf(name))
        if (ok) {
          const tname = initFiles.get(name)
          if (tname) initSent.add(tname)
          const sm = SEG_NAME_RE.exec(name)
          if (sm) {
            // 字节数取不到也算传成功（putFile 已经把整个文件读进内存了，只是可能已被 ffmpeg 清掉），
            // 台账里 bytes 仅为展示统计，不参与清单拼装
            const rec = segs.get(name) || { variant: sm[1], seq: Number(sm[2]), seconds: 0, bytes: 0 }
            try { rec.bytes = fs.statSync(path.join(dir, name)).size } catch { /* 已被清 */ }
            segs.set(name, rec)
          } else {
            const vm = VARIANT_PL_RE.exec(name)
            if (vm) noteDurations(vm[1].toLowerCase())
          }
          if (!firstDone) {
            firstDone = true
            if (onFirstUpload) onFirstUpload()
          }
        }
        if (!ok && onUploadError) onUploadError(name)
      }
      if (!closed && stopping && drainWake) {
        const wake = drainWake
        drainWake = null
        wake()
      }
    } finally {
      pumping = false
    }
  }

  const watcher = fs.watch(dir, (_eventType, filename) => {
    if (closed || stopping || !filename) return
    const name = String(filename)
    if (name.endsWith('.tmp') || name.startsWith('.')) return
    if (!/\.(m3u8|m4s|mp4|ts)$/i.test(name)) return
    pending.add(name)
    pump()
  })

  // 启动即探测一次：若 ffmpeg 在 watch 建立前已写出 init，这里补传
  pump()

  return {
    /**
     * 关播定版回放：排空待传队列 → 逐档拼 VOD 清单 PUT 到 {roomId}/replay/{档}.m3u8
     * → 再拼一份主清单 PUT 到 {roomId}/replay/index.m3u8（后端只签后者，名字沿用即零改动）。
     * resolve 出统计信息（无分片 / init 未上传 / 清单 PUT 失败时为 null），不抛错——
     * 回放生成失败绝不能拖死关播流程。
     */
    async finalizeReplay() {
      stopping = true
      try { watcher.close() } catch { /* 已关 */ }
      // 队列里可能正压着没传的文件：watcher 已关，不会再有人触发泵，这里手动起一次
      pump()
      // 等正在传那一个传完 + 队列排空；8 秒为上限——本地环回 PUT 一次十毫秒量级，
      // 真卡住时宁可少几秒画面也不能把关播的 await 挂死
      if (pumping || pending.size) {
        await Promise.race([
          new Promise((resolve) => { drainWake = resolve }),
          new Promise((resolve) => setTimeout(resolve, 8000))
        ])
      }
      closed = true
      pending.clear()
      if (!streamKey) return null
      const masterAttrs = readLiveMasterAttrs(dir)
      const byTier = new Map()
      for (const s of segs.values()) {
        const arr = byTier.get(s.variant) || []
        arr.push(s)
        byTier.set(s.variant, arr)
      }
      const entries = []
      let totalBytes = 0
      // 按 ladder 顺序（原画→最低档）逐个出清单：第一档成了就拿它作对外统计口径
      for (const tier of ladder) {
        // 那一档的 init 从未传上去（开场就被断 / 该档压根没跑起来）时清单解不了码，跳过它
        if (!initSent.has(tier.name)) continue
        const list = (byTier.get(tier.name) || []).sort((a, b) => a.seq - b.seq)
        const text = buildVodPlaylist(tier, list, streamKey)
        if (!text) continue
        if (!(await putText(`${roomBase}/replay/${tier.name}.m3u8`, text, 'application/vnd.apple.mpegurl'))) continue
        let seconds = 0
        let bytes = 0
        for (const s of list) {
          seconds += s.seconds || 1
          bytes += s.bytes || 0
        }
        totalBytes += bytes
        const attr = masterAttrs.get(tier.name) || {}
        entries.push({
          name: tier.name,
          // 实测均值优先；台账字节拿不到（都被 delete_segments 清掉了）时回落到该档设定码率，
          // BANDWIDTH=0 会让 hls.js 的自适应排序完全失去意义
          bandwidth: seconds > 0 && bytes > 0 ? Math.round((bytes * 8) / seconds) : tier.bitrateKbps * 1000,
          // 主清单也没给出分辨率时（极少：本地 index.m3u8 已被轮空），按 16:9 估一个给人看的标签
          resolution: attr.resolution || `${Math.round((tier.height * 16) / 9 / 2) * 2}x${tier.height}`,
          codecs: attr.codecs || '',
          segmentCount: list.length,
          durationSeconds: Math.round(seconds)
        })
      }
      const master = buildReplayMaster(entries)
      if (!master) return null
      const url = `${roomBase}/replay/index.m3u8`
      if (!(await putText(url, master, 'application/vnd.apple.mpegurl'))) return null
      console.log(
        `[live] 回放清单已生成：${entries.length} 档(${entries.map((e) => e.name).join('/')}) / `
        + `${entries[0].segmentCount} 片 / ${entries[0].durationSeconds}s`
      )
      return {
        segmentCount: entries[0].segmentCount,
        durationSeconds: entries[0].durationSeconds,
        bytes: totalBytes,
        tiers: entries.map((e) => e.name),
        url
      }
    },
    close() {
      closed = true
      pending.clear()
      if (drainWake) {
        const wake = drainWake
        drainWake = null
        wake()
      }
      try { watcher.close() } catch { /* 已关 */ }
    }
  }
}

/**
 * 停止推流并清理临时目录。reason 仅用于日志/事件。
 *
 * <p>{@code options.replay} 为真时（主播主动关播、ffmpeg 意外退出）先做一次回放定版：
 * kill 掉 ffmpeg → 排空上传队列 → 把整场画面写成 VOD 清单 PUT 上去 → 才清本地临时目录。
 * 所以它是 async 的；resolve 出回放统计（无内容时为 null）。不关心回放的调用点
 * （启动失败、退出前的收尾）照旧直接调用即可，返回的 Promise 没人等。
 */
async function closeLivePush(reason, options) {
  if (!active) return null
  const { proc, uploader, dir, send } = active
  active = null
  let replay = null
  try {
    if (proc && !proc.killed) {
      // Windows 下 ffmpeg 收不到 SIGTERM，直接 kill；它无子进程，kill 即可终结
      proc.kill()
    }
  } catch { /* 忽略 */ }
  if (uploader) {
    if (options && options.replay) {
      try {
        replay = await uploader.finalizeReplay()
      } catch (e) {
        console.warn('[live] 回放清单生成失败:', (e && e.message) || e)
      }
    }
    try { uploader.close() } catch { /* 忽略 */ }
  }
  // 临时目录异步删，删不掉（文件句柄未释放）也不阻塞；下次开播用新目录
  if (dir) {
    setTimeout(() => { try { fs.rmSync(dir, { recursive: true, force: true }) } catch { /* 忽略 */ } }, 500)
  }
  if (send) {
    // replay 随事件一起上报：ffmpeg 自己崩掉时前端不会再走 stopPush，只能从事件里拿到统计
    try { send('im:live-state', { state: 'stopped', reason: reason || 'manual', replay }) } catch { /* 窗口已关 */ }
  }
  return replay
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
    // GOP = 1 秒：与 -hls_time 1 对齐，每秒一片。关键帧比 2s 片密一倍，码率开销略涨（~10%），
    // 换取端到端延迟降约 3~5s（生产头/轮询周期/播放器回退同步减半）
    const gop = fps
    const enc = await detectLiveEncoder(ffmpegPath)
    const inputArgs = await buildInputArgs(ffmpegPath, sourceType, fps, deviceName, display)

    // 阶梯按原画向下派生，并用采集源真实高度砍掉放大了也没用的档位
    const capHeight = sourceType === 'camera' ? 0 : sourceHeight(display)
    const ladder = buildLadder(height, bitrateKbps, capHeight)

    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'im-live-'))
    const baseDir = dirBaseOf(pushUrl)

    const args = buildFfmpegArgs({ inputArgs, ladder, fps, gop, encArgs: enc.args, dir })

    let proc
    try {
      // cwd 必须设为临时目录：-hls_fmp4_init_filename 给的是相对名 init_%v.mp4，ffmpeg 会把它
      // 写到进程 cwd；不设 cwd 时 init 落到 Electron 工作目录而非 dir，上传泵在 dir 里
      // 永远找不到它，观众端缺 fMP4 初始化段解不了码（黑屏/转圈）。设 cwd=dir 后 init 正确落进
      // dir，且清单里 EXT-X-MAP 仍是相对引用（已实验验证）。
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
      ladder,
      () => { try { send('im:live-state', { state: 'pushing', encoder: enc.name, tiers: ladder.map((t) => t.label) }) } catch { /* 窗口已关 */ } },
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
        closeLivePush('ffmpeg-exit', { replay: true })
        return
      }
      if (enc.hw && !retriedSoft) {
        retriedSoft = true
        // 硬编运行期失败（编译进来但本机无对应驱动）：回落软编重开
        try { send('im:live-state', { state: 'starting', encoder: 'libx264', note: '硬件编码失败，回落软件编码' }) } catch { /* 忽略 */ }
        active = null
        try { uploader.close() } catch { /* 忽略 */ }
        // 整条命令用软编参数重建（而非在原 args 里定位替换 -c:v 段，那样易碎）：
        // 与首次 spawn 共用同一个阶梯构建器，档位与码率绑定关系保持不变，观众端的档位列表也不会突然缩水
        const rebuilt = buildFfmpegArgs({ inputArgs, ladder, fps, gop, encArgs: SOFT_ENC_ARGS, dir })
        let retryProc
        try {
          // 与首次 spawn 一样必须带 cwd=dir：阶梯下 init_%v.mp4 是相对名，ffmpeg 按进程 cwd 落盘，
          // 不带 cwd 会落到 Electron 工作目录，上传泵在 dir 里找不到（已知坑）
          retryProc = spawn(ffmpegPath, rebuilt, { windowsHide: true, cwd: dir })
        } catch {
          closeLivePush('soft-retry-spawn-error')
          return
        }
        const retrySession = { proc: retryProc, uploader: null, dir, send }
        active = retrySession
        retryProc.stderr.on('data', (buf) => { stderrTail = (stderrTail + buf.toString()).slice(-2000) })
        retrySession.uploader = startUploader(
          dir,
          baseDir,
          ladder,
          () => { try { send('im:live-state', { state: 'pushing', encoder: 'libx264', tiers: ladder.map((t) => t.label) }) } catch { /* 忽略 */ } },
          () => { /* 分片失败已在泵内处理 */ }
        )
        retryProc.on('close', (rc) => {
          if (active === retrySession) {
            if (rc !== 0) {
              try { send('im:live-state', { state: 'error', error: `推流中断（code=${rc}）：${stderrTail.slice(-300)}` }) } catch { /* 忽略 */ }
            }
            closeLivePush('ffmpeg-exit', { replay: true })
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
      closeLivePush('ffmpeg-exit', { replay: true })
    })

    // spawn 成功即认为已启动，立刻返回；后续状态变化经 'im:live-state' 事件推给渲染进程
    // tiers 回给前端展示「本场已出哪几档」，让主播知道自己开播面板里选的 2K 有没有真的铺开
    return { started: true, dir, encoder: enc.name, tiers: ladder.map((t) => t.label) }
  }))

  ipcMain.handle('im:live-stop-push', wrap(async (payload) => {
    // payload.replay === false：后端关了回放开关（前端从开播响应里知道），连清单都不生成
    const opt = { replay: !payload || payload.replay !== false }
    const replay = await closeLivePush('manual', opt)
    return { stopped: true, replay }
  }))

  ipcMain.handle('im:live-query-state', wrap(() => (active ? { pushing: true } : { pushing: false })))

  const { app } = require('electron')
  app.on('before-quit', () => closeLivePush('app-quit'))
}

module.exports = { setupLiveBridge, closeLivePush }
