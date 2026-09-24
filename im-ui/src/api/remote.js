import http from './request'

/**
 * 远程控制 REST 接口（控制面）。
 *
 * 数据面（屏幕帧/输入事件/文件块）走 utils/remoteWs.js 的专用 WebSocket，
 * 这里只有设备清单、邀请、轮询授权进度与审计查询。
 * 授权结果通过轮询 fetchRemoteSession 获取：后端刻意没为「同意/拒绝」
 * 再开一条推送通道——授权是一次性、秒级容忍度的动作，2 秒轮询更简单可靠。
 */

/** 我的被控设备列表：status 0 离线 / 1 空闲 / 2 忙 / 3 拒绝接入 */
export function fetchRemoteDevices() {
  return http.get('/remote/devices')
}

/** 发起远程邀请，返回 { sessionId, deviceName, permission }，sessionId 为字符串 */
export function inviteRemote({ deviceId, permission = 'operate' }) {
  return http.post('/remote/session/invite', { deviceId, permission })
}

/** 凭识别码发起远程邀请（ToDesk 式跨账号，被控端无需登录账号） */
export function inviteRemoteByCode({ code, permission = 'operate' }) {
  return http.post('/remote/session/invite-by-code', { code, permission })
}

/** 会话详情（轮询授权进度）：active 后含一次性 ticket 与会话 aesKey */
export function fetchRemoteSession(sessionId) {
  return http.get(`/remote/session/${sessionId}`, { silent: true })
}

/** 控制端主动结束会话 */
export function endRemoteSession(sessionId) {
  return http.post(`/remote/session/${sessionId}/end`)
}

/** 我的远程会话分页 */
export function fetchRemoteSessionPage(params) {
  return http.get('/remote/session/page', { params })
}

/** 会话审计分页：文件删除/结束进程/cmd/电源/输入拦截等 */
export function fetchRemoteAudit(sessionId, params) {
  return http.get(`/remote/session/${sessionId}/audit`, { params })
}
