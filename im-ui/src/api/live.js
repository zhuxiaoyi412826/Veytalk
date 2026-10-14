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

/* ============================================================================
 * 直播页「视频」板块：短视频信息流
 *
 * 视频与封面本体的上传复用通用文件接口（api/file.js 的 uploadFileSmart，
 * bizType 传 video / video_cover）；这里只负责信息流记录的增删查与播放计数。
 * 返回的 fileUrl / coverUrl 是长期受控地址，前端经 utils/media.js 的 mediaUrl 取 blob 播放/渲染。
 * ========================================================================== */

/**
 * 视频分页：仅正常状态视频，按发布时间倒序。
 * @param {{ current?: number, size?: number }} params
 */
export function fetchVideoPage(params) {
  return http.get('/live/video/page', { params })
}

/**
 * 发布视频：先用 bizType=video 上传拿到 fileId（可选再用 video_cover 传封面），再建信息流记录。
 * @param {{ title: string, fileId: string, coverFileId?: string, duration?: number,
 *           width?: number, height?: number, size: number, ext?: string, contentType?: string }} body
 */
export function createVideo(body) {
  return http.post('/live/video', body)
}

/** 删除视频：仅上传者本人可删；记录不存在后端幂等返回成功 */
export function deleteVideo(id) {
  return http.delete(`/live/video/${id}`)
}

/** 播放上报：点开视频时 +1，静默失败不打扰观看 */
export function reportVideoPlay(id) {
  return http.post(`/live/video/${id}/play`, {}, { silent: true })
}
