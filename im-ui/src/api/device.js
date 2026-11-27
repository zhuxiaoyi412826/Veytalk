import http from './request'

/**
 * 登录设备与账号安全记录。
 *
 * 后端只认当前登录态，这些接口一律不传 userId（传了也没有）：一旦开了「查别人的设备」这个口，
 * IP + 登录时间合起来就是一份行踪记录。
 */

/** 我的登录设备列表，按最近登录时间倒序 */
export function fetchDevices() {
  return http.get('/device/list')
}

/**
 * 我的登录记录（登录 / 注销 / 被顶号 / 撤销设备 / 凭证变更）。
 * 后端最多回 50 条，只记 IP 不记城市。
 */
export function fetchLoginRecords(limit = 20) {
  return http.get('/device/records', { params: { limit } })
}

/** 撤销一台设备：注销它的会话并断掉长连接，不禁止它下次登录 */
export function revokeDevice(id) {
  return http.delete(`/device/${id}`)
}

/** 除本机以外全部下线，返回 { count } */
export function logoutOtherDevices() {
  return http.post('/device/logout-others')
}
