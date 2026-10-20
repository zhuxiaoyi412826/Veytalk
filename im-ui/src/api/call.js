import http from './request'

/**
 * 语音视频通话（1:1 与群通话，WebRTC）控制面接口。
 *
 * 与远控/直播同构：这里只有「控制面」——媒体本体走浏览器/Electron 原生 WebRTC 点对点直连，
 * 信令（invite/accept/offer/answer/ice/hangup 等）走已认证的 WebSocket 长连接（见 ws/dispatch.js）。
 * REST 只负责下发 ICE 服务器清单：STUN 供公网 NAT 探测、TURN 走 coturn REST 临时凭证（可选）。
 *
 * 地址、凭证都由后端按当前用户签发，前端不写死任何 STUN/TURN，避免换环境就要改代码。
 */

/**
 * 拉取本端 ICE 配置。
 * @returns {Promise<{ enabled: boolean, iceServers: Array<{ urls: string|string[], username?: string, credential?: string }>,
 *          groupEnabled: boolean, groupMaxSlots: number }>}
 *          enabled=false 时通话整体关闭，iceServers 为空数组，前端据此禁用通话入口。
 */
export function getCallConfig() {
  return http.get('/call/config', { silent: true })
}

/**
 * 查本群此刻有没有人正开着群通话。
 *
 * 只在「打开这个群聊」时调一次，之后的增减全靠下行 groupcall-signal 的
 * member-join / member-leave / end 帧维护——服务端不会往全群广播「开了一路通话」
 * （im-websocket 不持有成员名单，星型约束），所以未进房的人中途看不到实时人数。
 *
 * @param {string|number} groupId 群 ID（雪花 id 按字符串传，避开 JS 安全整数截断）
 * @returns {Promise<null|{ callId: string, groupId: string, mediaType: string, fromUserId: string,
 *          members: Array<{ userId: string, status: string, muted: boolean, cameraOff: boolean }> }>}
 *          无进行中通话时 data 为空
 */
export function getGroupActiveCall(groupId) {
  return http.get('/call/group/active', { params: { groupId }, silent: true })
}
