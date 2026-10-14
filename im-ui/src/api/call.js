import http from './request'

/**
 * 语音视频通话（1:1 WebRTC）控制面接口。
 *
 * 与远控/直播同构：这里只有「控制面」——媒体本体走浏览器/Electron 原生 WebRTC 点对点直连，
 * 信令（invite/accept/offer/answer/ice/hangup 等）走已认证的 WebSocket 长连接（见 ws/dispatch.js）。
 * REST 只负责下发 ICE 服务器清单：STUN 供公网 NAT 探测、TURN 走 coturn REST 临时凭证（可选）。
 *
 * 地址、凭证都由后端按当前用户签发，前端不写死任何 STUN/TURN，避免换环境就要改代码。
 */

/**
 * 拉取本端 ICE 配置。
 * @returns {Promise<{ enabled: boolean, iceServers: Array<{ urls: string|string[], username?: string, credential?: string }> }>}
 *          enabled=false 时通话整体关闭，iceServers 为空数组，前端据此禁用通话入口。
 */
export function getCallConfig() {
  return http.get('/call/config', { silent: true })
}
