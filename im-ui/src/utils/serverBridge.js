/**
 * 后端地址调试弹窗的统一桥。
 *
 * 为什么要有这层：弹窗原本只认 Electron 主进程的 IPC 配置通道（window.__IM_NATIVE__.server），
 * 而 Tauri / Capacitor 壳没有主进程通道可用——但它们有 localStorage 运行时覆盖
 * （utils/env.js 的 IM_SERVER_OVERRIDE_KEY，serverBase() 每次都实时读）。
 * 把两种落点适配成同一套 get/normalize/set/reset 接口，弹窗组件就能三壳复用同一份 UI，
 * 不必为 Tauri 再写一个阉割版弹窗。
 *
 * Web 同源部署返回 null：调用方（登录页 / 设置页）据此把入口整体隐藏。
 */
import {
  IM_FALLBACK_BASE,
  IM_SERVER_FALLBACK_ACTIVE_KEY,
  IM_SERVER_OVERRIDE_KEY,
  isCapacitor,
  isElectron,
  isTauri,
  serverBaseInfo
} from './env'
import { normalizeBase } from './servercfg'

const INVALID_MSG = '地址不合法：需要 http:// 或 https:// 开头（也可直接填 IP:端口），且不能带空格与账号密码'
const localPath = () => `localStorage:${IM_SERVER_OVERRIDE_KEY}`

/** Tauri / Capacitor 壳的桥：落点是 localStorage，读写同步完成，接口仍保持 async 与 Electron 桥同形 */
function localBridge() {
  const readOverride = () => (window.localStorage && window.localStorage.getItem(IM_SERVER_OVERRIDE_KEY)) || ''
  const get = async () => {
    const info = serverBaseInfo()
    return {
      baseUrl: info.baseUrl,
      source: info.source,
      builtIn: (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || '',
      fallbackBase: IM_FALLBACK_BASE,
      fallbackActive: info.source === 'fallback',
      env: '',
      configPath: localPath(),
      hasConfig: !!readOverride(),
      canReveal: false
    }
  }
  return {
    get,
    normalize: async (baseUrl) => {
      const normalized = normalizeBase(baseUrl)
      if (!normalized) throw new Error(INVALID_MSG)
      return { baseUrl: normalized }
    },
    set: async (baseUrl) => {
      const normalized = normalizeBase(baseUrl)
      if (!normalized) throw new Error(INVALID_MSG)
      window.localStorage.setItem(IM_SERVER_OVERRIDE_KEY, normalized)
      return { baseUrl: normalized, source: 'local', configPath: localPath() }
    },
    reset: async () => {
      window.localStorage.removeItem(IM_SERVER_OVERRIDE_KEY)
      // 回落标记一并清：恢复内置默认就该回到「下次启动重新探域名」的干净状态
      window.localStorage.removeItem(IM_SERVER_FALLBACK_ACTIVE_KEY)
      return get()
    }
  }
}

/** 当前壳的调试桥：Electron 走主进程 IPC，Tauri / Capacitor 走 localStorage，Web 返回 null */
export function getServerBridge() {
  if (isElectron()) {
    return (typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.server) || null
  }
  if (isTauri() || isCapacitor()) return localBridge()
  return null
}
