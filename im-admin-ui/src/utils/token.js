/**
 * 后台登录凭证的读写。
 *
 * 与 im-ui 的 utils/token 是两份独立实现，键名也不同（{@code im_admin_token}）：
 * 两个工程的 dev server 端口不同、origin 不同，localStorage 本就互不可见，
 * 但键名仍旧要分开写清楚——将来若有人把它们放到同一个域名下部署，
 * 共用键会让「登录聊天端」顺手把后台身份也带上，那是不该有的耦合。
 *
 * tokenName 一并存下来：它由后端 LoginVO 返回，对应 sa-token.token-name 配置。
 * 写死 'satoken' 在后端改名后会全线 1002，而且报错看不出是头名字不对。
 */

const TOKEN_KEY = 'im_admin_token'
const TOKEN_NAME_KEY = 'im_admin_token_name'

/** Sa-Token 的默认头名，仅在本地没有记录时兜底 */
const DEFAULT_TOKEN_NAME = 'satoken'

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
