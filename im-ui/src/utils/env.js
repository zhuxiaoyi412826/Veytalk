/**
 * 运行环境与后端地址解析。
 *
 * 同一份前端产物要在两种宿主里跑：
 * - 浏览器 Web 部署：前端与后端同源（开发期走 vite proxy、生产走 Nginx 反代），
 *   API 用相对路径 /api、WebSocket 按 location.host 推导即可，不需要任何绝对地址。
 * - Electron 桌面端：页面从 file:// 加载，既没有 vite proxy 也没有 Nginx，
 *   /api 会指向 file:// 自身、location.host 为空，必须改用远程后端的绝对地址。
 *
 * 绝对地址由 Electron 主进程经 preload 注入到 window.__IM_SERVER__.baseUrl
 * （见 electron/preload.js）。Web 环境下这个对象不存在，serverBase() 自然返回空串，
 * 于是所有判断都回落到同源逻辑——现有 Web 部署完全不受影响。
 */

/** 是否运行在 Electron 壳里（Electron 的 UA 固定含 "Electron" 字样） */
export function isElectron() {
  return typeof navigator !== 'undefined' && /electron/i.test(navigator.userAgent)
}

/** 是否运行在 Tauri 壳里（v2 无论 withGlobalTauri 与否都会注入 __TAURI_INTERNALS__） */
export function isTauri() {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

/** 是否运行在 Capacitor 原生壳里（安卓/iOS，原生桥会注入 window.Capacitor） */
export function isCapacitor() {
  if (typeof window === 'undefined') return false
  if (window.Capacitor) return true
  // 兜底：打包后的 Capacitor 页面由 WebView 以 https://localhost（或 http://localhost，无端口）加载，
  // 桥对象偶发注入失败时不能因此把整壳当 Web（否则 /api 会打到 WebView 自身，全部请求失败）。
  // 浏览器访问带端口的 localhost（5173/4173 等）不会命中这条。
  try {
    const { protocol, hostname, port } = window.location
    return (hostname === 'localhost' || hostname === '127.0.0.1') &&
      port === '' &&
      (protocol === 'https:' || protocol === 'http:')
  } catch (e) {
    return false
  }
}

/** 是否任意原生壳（Electron / Tauri / Capacitor）——凡是「非浏览器同源」的宿主都用它 */
export function isNativeShell() {
  return isElectron() || isTauri() || isCapacitor()
}

/** 后端地址运行时覆盖键（Tauri / Capacitor 壳）：「开发调试」弹窗改的就是它；Electron 走主进程配置文件那套 */
export const IM_SERVER_OVERRIDE_KEY = 'IM_SERVER_URL_OVERRIDE'
/** IP 回落激活标记键（Tauri / Capacitor 壳）：启动探测 ensureServerBase() 写入，下次启动域名又通时会清掉 */
export const IM_SERVER_FALLBACK_ACTIVE_KEY = 'IM_SERVER_FALLBACK_ACTIVE'
/**
 * 内置回落地址：与 electron/server-config.js 的 BUILT_IN_FALLBACK_BASE 同一台后端，公网 IP 直连 nginx 443。
 * 域名托管在未备案的大陆云上时会被运营商在 TLS 握手阶段 reset，没有主进程探测的壳（Tauri / Capacitor）
 * 靠启动探测 ensureServerBase() 切到本地址保证开箱可连。
 */
export const IM_FALLBACK_BASE = 'https://120.78.126.132'

/**
 * 远程后端根地址（协议+host+端口，不含 /api），Web 环境为空串。
 * Electron 由主进程经 preload 注入；Tauri 没有 preload，用编译期 Vite 环境变量（.env.tauri）。
 */
function serverBase() {
  if (typeof window !== 'undefined' && window.__IM_SERVER__ && window.__IM_SERVER__.baseUrl) {
    return window.__IM_SERVER__.baseUrl
  }
  if (isTauri() || isCapacitor()) {
    // 运行时覆盖优先于编译期默认值：热点/换网段导致后端 IP 变化时，
    // 在登录页改一下即可连上新地址，无需重打包（localStorage 每次请求实时读取）。
    const override = window.localStorage && window.localStorage.getItem(IM_SERVER_OVERRIDE_KEY)
    if (override) return override.replace(/\/+$/, '')
    // IP 回落层：启动探测判定域名不可达（如被备案拦截 reset）时切到内置公网 IP
    if (window.localStorage && window.localStorage.getItem(IM_SERVER_FALLBACK_ACTIVE_KEY) === '1') {
      return IM_FALLBACK_BASE
    }
    return (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || ''
  }
  return ''
}

/**
 * 当前生效的后端地址与它的来源，供设置页 / 调试弹窗显示，与 serverBase() 同口径：
 * Electron 读主进程注入；Tauri / Capacitor 按 覆盖 > 回落 > 编译期默认 分级；Web 返回空。
 */
export function serverBaseInfo() {
  if (typeof window !== 'undefined' && window.__IM_SERVER__ && window.__IM_SERVER__.baseUrl) {
    return { baseUrl: window.__IM_SERVER__.baseUrl, source: window.__IM_SERVER__.source || '' }
  }
  if (isTauri() || isCapacitor()) {
    const override = window.localStorage && window.localStorage.getItem(IM_SERVER_OVERRIDE_KEY)
    if (override) return { baseUrl: override.replace(/\/+$/, ''), source: 'local' }
    if (window.localStorage && window.localStorage.getItem(IM_SERVER_FALLBACK_ACTIVE_KEY) === '1') {
      return { baseUrl: IM_FALLBACK_BASE, source: 'fallback' }
    }
    return { baseUrl: (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || '', source: 'default' }
  }
  return { baseUrl: '', source: '' }
}

/**
 * 启动期探测（Tauri / Capacitor 壳）：域名不可达则激活 IP 回落层；Electron 有自己的主进程探测、Web 同源，都不需要。
 * 必须在 mount 之前调用（见 main.js）：axios 的 baseURL 与 WS 地址在模块加载时就算定，探测晚一步首屏就发错地址。
 * 探测只问「有没有 HTTP 应答」，不看状态码（1002 未登录也是可达的证据）；任何抛错（reset / 证书拒 / 超时）都算不可达。
 * 证书校验严格的壳（WKWebView / Android WebView）上，证书不受信与网络被拦都会算「不可达」而落 IP——
 * 那种场景 IP 自己能不能用取决于证书有没有 IP SAN，见打包文档。
 */
export async function ensureServerBase(timeoutMs = 2500) {
  if (!isTauri() && !isCapacitor()) return serverBase()
  const store = window.localStorage
  if (!store || store.getItem(IM_SERVER_OVERRIDE_KEY)) return serverBase()
  const compileBase = (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || ''
  if (!compileBase) return serverBase()
  const reachable = await new Promise((done) => {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    fetch(`${compileBase.replace(/\/+$/, '')}/api/auth/me`, { signal: controller.signal, cache: 'no-store' })
      .then(() => done(true))
      .catch(() => done(false))
      .finally(() => clearTimeout(timer))
  })
  if (reachable) {
    store.removeItem(IM_SERVER_FALLBACK_ACTIVE_KEY)
  } else {
    store.setItem(IM_SERVER_FALLBACK_ACTIVE_KEY, '1')
  }
  return serverBase()
}

/** axios 的 baseURL：桌面壳用「后端绝对地址 + /api」，Web 用同源相对路径 /api */
export function apiBaseURL() {
  const base = serverBase()
  return isNativeShell() && base ? `${base.replace(/\/+$/, '')}/api` : '/api'
}

/**
 * 媒体内容的请求前缀：专门给 fetchBlobMeta(rawUrl) 这类「rawUrl 已含 /api」的请求用。
 *
 * 后端存下来的受控地址（/api/file/download/{id}）本身就带 /api 前缀：
 * - Web 同源部署：相对路径直接解析到当前站点，前缀给空串即可；
 * - Electron：页面从 file:// 加载，相对路径会解析成 file:///api/...，必然失败，
 *   必须补上后端绝对地址（不含 /api，因为 rawUrl 自己带了）。
 */
export function mediaBaseURL() {
  const base = serverBase()
  return isNativeShell() && base ? base.replace(/\/+$/, '') : ''
}

/**
 * WebSocket 根地址（协议 + host，不含 /ws 路径与 query）：
 * Electron 把后端 http(s) 换成 ws(s)，Web 按当前页面协议与 host 推导。
 */
export function wsBaseURL() {
  const base = serverBase()
  if (isNativeShell() && base) {
    return base.replace(/^http/i, 'ws').replace(/\/+$/, '')
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
}

/**
 * Webhook 回调地址的基址（供外部系统 POST，必须是外部可达的绝对地址）：
 * - Electron：页面从 file:// 加载，location.origin 无意义，用注入的后端绝对地址；
 * - Web 同源部署：前端 origin 即后端反代入口，直接用 location.origin。
 * 后端 normalizeBase 只保留 scheme://host[:port]，因此多带的 /api 会被剥掉。
 */
export function webhookBase() {
  if (isNativeShell()) {
    const base = serverBase();
    if (base) {
      return base.replace(/\/+$/, '');
    }
  }
  return (typeof window !== 'undefined' && window.location.origin) || '';
}

/** 流媒体服务器端口：electron/live-media-server.js 默认 8088（局域网自测替身），换端口要同步改这里或把 play-base-url 配成绝对地址 */
const LIVE_MEDIA_PORT = 8088

/**
 * 直播播放地址的 host 前缀。后端下发的 playUrl 是相对路径（/hls/{roomId}/{streamKey}/index.m3u8?...）：
 * - Web：返回空串，浏览器按当前页面 origin 解析（开发期 vite proxy /hls→本机 8088，生产 Nginx 同域反代），
 *   播放地址于是永远跟随前端访问地址，DHCP 换 IP 无需改任何配置；
 * - Electron：页面从 file:// 加载，相对路径会解析成 file:///hls/... 必然失败，必须补绝对地址。
 *   ★ 这里取的是「后端所在机器」的 host，不是本机：媒体服务器与后端同机部署（自测拓扑），
 *   而桌面端可能装在另一台机器上——早先硬编码 http://127.0.0.1:8088 会让观众去自己机器上要流，
 *   跨机场景直播必挂（互动/礼物/点播走注入的后端绝对地址，所以只有直播坏）。
 *   现在优先用 preload 注入的后端 host + 8088，拿不到注入地址（老 preload / 地址被清空）才回落本机。
 * 生产若把 play-base-url 配成绝对域名，后端下发的 playUrl 就是 http(s) 开头，调用方直接用、不经过这里。
 */
export function livePlayBase() {
  // Tauri / Capacitor：流媒体服务器地址编译期注入（.env.tauri / .env.capacitor 的 VITE_IM_MEDIA_BASE）
  if (isTauri() || isCapacitor()) {
    // 服务器地址被运行时覆盖时（热点换 IP）：流媒体与后端同机部署，跟随覆盖地址的 host，只换端口为 8088
    const override = window.localStorage && window.localStorage.getItem(IM_SERVER_OVERRIDE_KEY)
    if (override) return override.replace(/\/+$/, '').replace(/:\d+$/, '') + ':8088'
    // IP 回落层：流媒体口与聊天口同一台 nginx，跟随回落地址（同域复用 443 证书）
    if (window.localStorage && window.localStorage.getItem(IM_SERVER_FALLBACK_ACTIVE_KEY) === '1') {
      return IM_FALLBACK_BASE
    }
    return import.meta.env.VITE_IM_MEDIA_BASE || 'http://127.0.0.1:8088'
  }
  if (!isElectron()) return ''
  const base = serverBase()
  if (base) {
    try {
      const u = new URL(base)
      if (u.hostname) return `${u.protocol}//${u.hostname}:${LIVE_MEDIA_PORT}`
    } catch (e) {
      // 注入的地址不是合法 URL（脏数据/手工改坏），回落到本机而不是抛异常，保证还能播本机那场
    }
  }
  return `http://127.0.0.1:${LIVE_MEDIA_PORT}`
}

/**
 * 推流地址的 host 校正（只给 Electron 用）。
 *
 * 后端 push-base-url 默认 `http://127.0.0.1:8088/hls`，隐含前提是「推流端与媒体服务器同在一台机器」。
 * 桌面端装在另一台机器时，主播的 ffmpeg 会把分片 PUT 到自己本机的 8088 → 连不上 → 开不了播。
 * 这里把回环 host 换成媒体面真实 host（与播放面同一台），path / query 逐字保留（streamKey 与签名都在 path 里，动不得）。
 * 已经是绝对域名/局域网地址的 pushUrl 说明是运维显式配的，原样返回不插手。
 */
export function remapPushUrl(pushUrl) {
  if (!isElectron() || !pushUrl) return pushUrl
  const target = livePlayBase()
  if (!target) return pushUrl
  try {
    const u = new URL(pushUrl)
    if (!/^(127\.0\.0\.1|localhost)$/i.test(u.hostname)) return pushUrl
    const t = new URL(target)
    u.protocol = t.protocol
    u.host = t.host
    return u.toString()
  } catch (e) {
    return pushUrl
  }
}
