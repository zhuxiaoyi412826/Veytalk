import Taro from '@tarojs/taro'

const SERVER_KEY = 'veytalk.server'
const DEVICE_UID_KEY = 'veytalk.device_uid'
export const DEFAULT_SERVER = 'https://im.dsaol.asia'
/** DeviceType 枚举只认 web/pc/android/ios/mini，小程序这一端固定 mini */
const DEVICE_TYPE = 'mini'
/** 设备友好名上限，与后端 DeviceIds.MAX_NAME_LENGTH 对齐 */
const DEVICE_NAME_MAX = 50

let cachedUid = ''

/**
 * 本机安装实例 ID：首次生成一个随机串存进 storage，之后一直复用。
 *
 * 小程序拿不到设备唯一标识（微信不让），但 storage 在同一台机器的同一个小程序上是稳定的，
 * 删掉小程序会重新生成——这与「换了一台机器」在业务上等价，可以接受。
 * 绝不能回退成裸类型码：那等于把同端顶号关掉，而且后端会把它当成存量数据不再参与顶号。
 */
export function getDeviceUid(): string {
  if (cachedUid) return cachedUid
  let uid = ''
  try {
    uid = (Taro.getStorageSync(DEVICE_UID_KEY) as string) || ''
  } catch {
    uid = ''
  }
  if (!uid) {
    uid = newUid()
    try {
      Taro.setStorageSync(DEVICE_UID_KEY, uid)
    } catch {
      // 写不进去只影响下次生成一个新 ID，不能因此挡住登录
    }
  }
  cachedUid = uid
  return uid
}

function newUid(): string {
  const hex = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0')
  return `${hex()}${hex()}-${hex()}-${hex()}-${hex()}-${hex()}${hex()}${hex()}`
}

/** 设备位 类型:实例（对应 Web 端 token.js 的 getDeviceId），登录/注册/WS 票据三处共用 */
export function getDeviceId(): string {
  return `${DEVICE_TYPE}:${getDeviceUid()}`
}

let cachedName: string | null = null

/**
 * 设备友好名（品牌 + 型号），只用于展示与被顶时的提示文案，取不到就不发这个头。
 *
 * 只留可打印 ASCII：这个值走 HTTP 请求头，中文型号名（部分安卓机型）传过去就是一串乱码；
 * 后端拿不到时会用 User-Agent 兜底，宁缺勿错。
 */
export function getDeviceName(): string {
  if (cachedName !== null) return cachedName
  let raw = ''
  try {
    const info = Taro.getSystemInfoSync()
    raw = [info.brand, info.model].filter(Boolean).join(' ')
  } catch {
    raw = ''
  }
  cachedName = raw
    .split('')
    .filter((ch) => ch.charCodeAt(0) >= 0x20 && ch.charCodeAt(0) <= 0x7e)
    .join('')
    .replace(/\s{2,}/g, ' ')
    .trim()
    .slice(0, DEVICE_NAME_MAX)
  return cachedName
}

export function normalizeServer(value: string): string {
  return value.trim().replace(/\/+$/, '')
}

/**
 * 是否为纯 IP 地址形式的服务器。
 * 线上证书为 CN=im.dsaol.asia，未把 IP 写进 SAN，用 IP 直连一律
 * ERR_CERT_COMMON_NAME_INVALID；微信小程序本身也不接受 IP 作为合法域名。
 */
export function isIpServer(value: string): boolean {
  return /^https?:\/\/\d{1,3}(\.\d{1,3}){3}(:\d+)?$/i.test(normalizeServer(value))
}

export function getServer(): string {
  const saved = Taro.getStorageSync(SERVER_KEY) as string
  const stored = saved ? normalizeServer(saved) : ''
  // 历史脏配置可能存了 IP，就地清掉并回退域名，否则每次请求都会卡在证书校验
  if (stored && isIpServer(stored)) {
    Taro.removeStorageSync(SERVER_KEY)
    return DEFAULT_SERVER
  }
  return stored || DEFAULT_SERVER
}

export function setServer(value: string): string {
  const server = normalizeServer(value)
  if (isIpServer(server)) return getServer()
  Taro.setStorageSync(SERVER_KEY, server)
  return server
}

export function resetServer(): void {
  Taro.removeStorageSync(SERVER_KEY)
}

export function getApiBase(): string {
  return `${getServer()}/api`
}

export function getWsBase(): string {
  return getServer().replace(/^http:/, 'ws:').replace(/^https:/, 'wss:')
}

export function resolveUrl(path?: string): string {
  if (!path) return ''
  if (/^(https?:|wss?:|data:|blob:|wxfile:|file:|\/\/)/.test(path)) return path
  return `${getServer()}${path.startsWith('/') ? path : `/${path}`}`
}
