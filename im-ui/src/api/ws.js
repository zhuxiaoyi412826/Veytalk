import http from './request'

/**
 * 换取 WebSocket 连接票据。
 *
 * 握手时票据只能放在查询参数里：浏览器的 WebSocket 构造器不允许自定义请求头，
 * 这是平台限制而不是后端的设计选择。因此后端签发一张一次性短票（默认 60 秒）
 * 代替把登录 token 直接挂到 URL 上 —— 票据泄露也伪造不出登录态。
 */
export function fetchWsTicket(deviceId) {
  return http.post('/ws/ticket', null, { params: { deviceId } })
}
