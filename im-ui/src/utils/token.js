/**
 * 上送后端的登录凭证读写与设备位。
 *
 * 后端把 {@code deviceId} 这个字段经 {@code DeviceIds.normalize} 清洗，
 * 只认「类型:实例」与裸类型码两种形状，未知类型回落 web。
 *
 * 刻意做成不依赖 Pinia 的纯模块：axios 拦截器、WebSocket 客户端、路由守卫
 * 和 auth store 都要读 token，如果只有 store 里有一份，拦截器就得反向 import store，
 * 而 store 又 import api，形成循环依赖（Vite 下表现为拿到 undefined）。
 * 把凭证沉到这一层，四方都单向依赖它。
 *
 * tokenName 一并存下来：它由后端 LoginVO 返回，对应 sa-token.token-name 配置。
 * 写死 'satoken' 在配置改名后会全线 401，且报错信息里看不出是头名字不对。
 */

import { isElectron } from '@/utils/env'

const TOKEN_KEY = 'im_token'
const TOKEN_NAME_KEY = 'im_token_name'

/** Sa-Token 的默认头名，仅在本地没有记录时兜底 */
const DEFAULT_TOKEN_NAME = 'satoken'

/** 浏览器端在 DeviceType 枚举里的取值 */
const DEVICE_ID = 'web'

/**
 * 安装实例 ID（本机这一台浏览器 / 这一台桌面机）。
 *
 * 存 localStorage 而不是每次随机：随机值会让同一个浏览器的每个页签、每次刷新都算一台新设备，
 * 「浏览器只允许 3 台」立刻变成「刷新三次就被自己顶下线」。
 * 也不跟着 token 一起清（clearToken 不删它）：登出不等于换了机器。
 */
const DEVICE_UID_KEY = 'im_device_uid'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getTokenName() {
  return localStorage.getItem(TOKEN_NAME_KEY) || DEFAULT_TOKEN_NAME
}

export function setToken(token, tokenName) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  }
  if (tokenName) {
    localStorage.setItem(TOKEN_NAME_KEY, tokenName)
  }
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  // tokenName 与设备实例 ID 都保留：下次登录前拦截器仍需要一个正确的头名，而换登录账号不等于换机器
}

/** 设备类型码，与 {@link getDeviceId} 的类型段同源 */
export function getDeviceType() {
  return isElectron() ? 'pc' : DEVICE_ID
}

/**
 * 设备友好名（走 {@code X-Device-Name} 头），拿不准就留空让后端用 User-Agent 推。
 *
 * 浏览器侧返回空串：后端 uaHint 能从 UA 里认出「Chrome on Windows」，比前端自己拼的准。
 * 桌面端必须显式报：Electron 的 UA 同样带 Chrome/ 与 Windows NT 特征，只看 UA 会被认成
 * 「Chrome on Windows」，用户在被踢通知的「设备：Chrome on Windows」里认不出那是桌面端。
 *
 * 只给 ASCII：请求头按 ISO-8859-1 传，中文值到后端是一串乱码（与安卓端同一个约束）。
 */
export function getDeviceName() {
  return isElectron() ? 'IM Desktop' : ''
}

/**
 * 设备位 类型:实例。
 *
 * 后端把它拆成 Sa-Token 的 deviceType + deviceId 两段，同时用作 WebSocket 注册表的第二层键、
 * Redis 在线键的 field、im_user_device 的行定位（四处同源，否则「会话踢中了、socket 还活着」）。
 * Electron 桌面端报 pc:xxx，与浏览器 web:xxx 分占不同设备位，
 * 同一台机器上「浏览器 + 桌面端」可以共存。
 *
 * 历史注：本函数曾经只返回裸类型码，那时后端分不清同一个浏览器与另一个浏览器，
 * 「同端只留 N 台」根本无法表达（理由见 DeviceIds 的类注释）。
 * 裸类型码在今天仍然是合法的「老客户端」形状，只是不参与顶号。
 */
export function getDeviceId() {
  return `${getDeviceType()}:${getDeviceUid()}`
}

/**
 * 取或建本机实例 ID。
 *
 * 优先 crypto.randomUUID（安全上下文里可用）；http 明文访问局域网 IP 时它不存在，
 * 因此必须自己拼一个形状相近的 v4 十六进制串而不是直接报错——那个场景下
 * navigator.mediaDevices 同样拿不到，正是本项目常见的联调方式。
 */
export function getDeviceUid() {
  let uid = localStorage.getItem(DEVICE_UID_KEY) || ''
  if (!uid) {
    uid = newUid()
    localStorage.setItem(DEVICE_UID_KEY, uid)
  }
  return uid
}

function newUid() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID()
  }
  const hex = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0')
  // 8-4-4-4-12 的 UUID 形状，只用于设备标识、不当加密随机数用
  return `${hex()}${hex()}-${hex()}-${hex()}-${hex()}-${hex()}${hex()}${hex()}`
}
