import http from './request'

/**
 * 直播控制面 REST 接口。
 *
 * 与远控同理，这里只有「控制面」——媒体字节（HLS 分片）由推流端 ffmpeg 直接 PUT 到
 * Nginx、观众用 hls.js 从 Nginx 拉流，弹幕走独立的 /ws/live 通道。REST 只负责
 * 开播下发推流信息、房间列表/详情、心跳续期与关播。
 *
 * 雪花 ID（roomId）经后端 SafeLongSerializer 已序列化为字符串，前端当不透明字符串用即可。
 */

/**
 * 开播：新建一场直播并下发推流信息。
 * @param {{ title: string, cover?: string, notice?: string, sourceType?: string, resolution?: string, bitrateKbps?: number }} body
 * @returns {Promise<{ roomId: string, streamKey: string, pushUrl: string, playUrl: string, heartbeatSeconds: number, danmakuWs: string }>}
 *          streamKey 只此一次返回，推流端务必留存；pushUrl 是给 ffmpeg 的 PUT 目标。
 */
export function startLive(body) {
  return http.post('/live/start', body)
}

/** 关播：主播主动结束直播；房间已结束则后端幂等返回成功 */
export function stopLive(roomId) {
  return http.post(`/live/${roomId}/stop`)
}

/**
 * 撤销未推流的直播：建房成功但 startPush 失败时调用。
 * 后端对从未收到推流心跳的房间直接删除，不留一个空场的「已结束」房；
 * 已推过流的降级为普通关播。房间不存在时幂等返回成功。
 */
export function abortLive(roomId) {
  return http.post(`/live/${roomId}/abort`)
}

/**
 * 推流心跳：推流端按 heartbeatSeconds 周期调用续期。
 * 返回 false 表示房间已不存在或已结束，推流端必须立即停止 ffmpeg。
 */
export function liveHeartbeat(roomId) {
  return http.post(`/live/${roomId}/heartbeat`, {}, { silent: true })
}

/** 房间详情：含签名播放地址与弹幕端点（仅直播中），进房时现取现用，不要长期缓存 */
export function fetchLiveRoom(roomId) {
  return http.get(`/live/${roomId}`, { silent: true })
}

/**
 * 房间分页：直播中的房间恒排最前，其次按开播时间倒序。
 * 后端会过滤掉已结束且关播超过阈值（默认 60 分钟）的房间，前端无需再筛。
 * @param {{ current?: number, size?: number, status?: number }} params status 留空为全部（1 直播中 2 已结束 3 已封禁）
 */
export function fetchLivePage(params) {
  return http.get('/live/page', { params })
}

/** 我的直播：我最近的一场（直播中优先），用于开播面板恢复现场；一场都没开过返回 null */
export function fetchMyLive() {
  return http.get('/live/mine', { silent: true })
}
