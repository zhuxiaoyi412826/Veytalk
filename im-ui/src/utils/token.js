/**
 * 登录凭证的读写。
 *
 * 刻意做成不依赖 Pinia 的纯模块：axios 拦截器、WebSocket 客户端、路由守卫
 * 和 auth store 都要读 token，如果只有 store 里有一份，拦截器就得反向 import store，
 * 而 store 又 import api，形成循环依赖（Vite 下表现为拿到 undefined）。
 * 把凭证沉到这一层，四方都单向依赖它。
 *
 * tokenName 一并存下来：它由后端 LoginVO 返回，对应 sa-token.token-name 配置。
 * 写死 'satoken' 在配置改名后会全线 401，且报错信息里看不出是头名字不对。
 */

const TOKEN_KEY = 'im_token'
const TOKEN_NAME_KEY = 'im_token_name'

/** Sa-Token 的默认头名，仅在本地没有记录时兜底 */
const DEFAULT_TOKEN_NAME = 'satoken'

/** 浏览器端在 DeviceType 枚举里的取值 */
const DEVICE_ID = 'web'

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
  // tokenName 保留：下次登录前拦截器仍需要一个正确的头名
}

/**
 * 设备标识。
 *
 * 后端把这个值经 DeviceType.codeOf 归一化，只认 web / pc / android / ios / mini 五种，
 * 未知值一律当 web。所以这里没必要造一个「每台浏览器唯一」的随机串 ——
 * 它会被静默丢掉，只会让人误以为两个标签页算两个设备。
 *
 * 真实语义是：同一账号在两个浏览器标签页登录会互相顶下线（kickSameDevice 按 device 匹配），
 * 这是后端的既定设计，前端如实告知而不是假装能区分。
 */
export function getDeviceId() {
  return DEVICE_ID
}
