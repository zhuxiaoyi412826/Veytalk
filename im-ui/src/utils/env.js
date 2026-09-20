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

/** Electron 主进程注入的远程后端根地址（协议+host+端口，不含 /api），Web 环境为空串 */
function serverBase() {
  return (typeof window !== 'undefined' && window.__IM_SERVER__ && window.__IM_SERVER__.baseUrl) || ''
}

/** axios 的 baseURL：Electron 用「后端绝对地址 + /api」，Web 用同源相对路径 /api */
export function apiBaseURL() {
  const base = serverBase()
  return isElectron() && base ? `${base.replace(/\/+$/, '')}/api` : '/api'
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
  return isElectron() && base ? base.replace(/\/+$/, '') : ''
}

/**
 * WebSocket 根地址（协议 + host，不含 /ws 路径与 query）：
 * Electron 把后端 http(s) 换成 ws(s)，Web 按当前页面协议与 host 推导。
 */
export function wsBaseURL() {
  const base = serverBase()
  if (isElectron() && base) {
    return base.replace(/^http/i, 'ws').replace(/\/+$/, '')
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
}
