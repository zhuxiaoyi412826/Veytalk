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
 * @param {{ title: string, cover?: string, notice?: string, sourceType?: string, resolution?: string, bitrateKbps?: number,
 *           password?: string, price?: number }} body
 *          password 非空即密码房；price>0 即付费房（需后端钱包开启）；两者都给按密码优先。
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
 * 解锁密码直播：密码正确后后端种解锁标记，返回解锁后的房间详情（这次带 playUrl）。
 * 错密码报错（11019），非密码房报错（11020）。
 */
export function unlockLiveRoom(roomId, password) {
  return http.post(`/live/${roomId}/unlock`, { password })
}

/**
 * 付费解锁直播：扣入场积分后返回解锁后的房间详情（带 playUrl）。余额不足报「积分余额不足」。
 * 幂等：已解锁再调不会二次扣款。主播本人恒免。
 */
export function payLiveRoom(roomId) {
  return http.post(`/live/${roomId}/pay`)
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

/**
 * 主播数据面板（只能看自己的，后端按登录态取 anchorId 聚合）。
 *
 * 两个口径必须知道（面板上也写了）：
 * 1) 概览只覆盖「窗口内最近 100 场」，sessionsCapped 为 true 时不要把概览当全量；
 * 2) 点赞只在 Redis 活 24 小时，所以只有 current.likeHeat 有点赞数，历史场次没有。
 * 礼物价值是虚拟积分且不会进主播钱包（送礼只扣送礼人），当观感指标看。
 *
 * @param {number} [days] 统计窗口天数，服务端夹到 [1,366]，默认 30
 * @returns {Promise<{windowDays: number, sessionLimit: number, sessionsCapped: boolean,
 *   overview: object, sessions: Array<object>, topGifters: Array<object>,
 *   topGiftTypes: Array<object>, current: object|null}>}
 */
export function fetchAnchorStats(days) {
  return http.get('/live/anchor/stats', { params: days ? { days } : {}, silent: true })
}

/* ============================================================================
 * 直播回放存档（零 DDL）
 *
 * 分片本体一直在媒体服务器上（推流时就是 PUT 到那里的），回放缺的只是一份不滚动的 VOD
 * 清单——那份清单由 Electron 主进程在关播时顺手生成并 PUT 上去，不经过本模块的 REST。
 * 这里三个接口只搬运「这一场有回放」、「现签一份地址」与「哪些场次可回看」，机理见 md/直播.md §15。
 * ========================================================================== */

/**
 * 上报回放：主播关播后把推流端的定版统计交给后端，写入入口标记（只存 Redis）。
 * @param {{ segmentCount: number, durationSeconds?: number, bytes?: number }} body
 */
export function reportReplay(roomId, body) {
  return http.post(`/live/${roomId}/replay`, body, { silent: true })
}

/**
 * 取回放：现签一份 VOD 清单地址（同直播那样不能长期缓存）。
 * @returns {Promise<{ playUrl: string, durationSeconds: number, segmentCount: number, bytes: number, reportAt: number } | null>}
 *          null 为「没有入口或入口已过期」，不报错
 */
export function fetchReplay(roomId) {
  return http.get(`/live/${roomId}/replay`, { silent: true })
}

/**
 * 可回看场次：按关播时间倒序列出「有回放入口」的已结束房间（大厅那条横条用）。
 *
 * 为什么要单独一份列表：回放入口标能活 retention-days（默认 1 天），而大厅对
 * 「已结束房间」只展示 ended-room-visible-minutes（默认 60 分钟），两者不同量级。
 * 不随带播放地址——签名有有效期，进了房间再 fetchReplay 现签。
 * @param {{ anchorId?: string|number, limit?: number }} params anchorId 留空为全部主播；limit 服务端夹到 [1,20]
 * @returns {Promise<Array<{ id: string, title: string, cover: string, anchorName: string, anchorAvatar: string, endTime: string, viewerTotal: number }>>}
 */
export function fetchReplayRooms(params) {
  return http.get('/live/replays', { params, silent: true })
}

/* ============================================================================
 * 直播连麦：信令走已认证的主 /ws 长连接（见 stores/linkmic.js），这里只留两个控制面 REST：
 * 拉本端连麦 ICE 配置、查房间当前在麦嘉宾列表。上麦申请/接受/拒绝/挂断都不是 REST，
 * 而是经 linkmic 帧的 linkAction 收发，与音视频通话同构。
 * ========================================================================== */

/**
 * 拉连麦配置与 ICE 服务器清单。
 * @returns {Promise<{ enabled: boolean, maxSlots: number, iceServers: Array<{ urls: string|string[], username?: string, credential?: string }> }>}
 *          enabled=false 时连麦整体关闭，前端据此隐藏上麦入口。
 */
export function fetchLinkmicConfig(roomId) {
  return http.get(`/live/${roomId}/linkmic/config`, { silent: true })
}

/**
 * 房间当前在麦嘉宾列表（观众进房时补齐初始态，之后靠 /ws/live 的 linkmic 帧驱动切换）。
 * @returns {Promise<Array<{ userId: string, name: string, avatar: string }>>}
 */
export function fetchLinkmicList(roomId) {
  return http.get(`/live/${roomId}/linkmic`, { silent: true })
}

/* ============================================================================
 * 弹幕房管（只对主播本人有意义）
 *
 * 路径里没有 roomId：禁言名单与屏蔽词都按<b>主播 id</b> 存（服务端 Redis），跨场次生效，
 * 因此没在开播时也能配置。roomId 只在「禁言/解禁」时作为可选参数带上，
 * 服务端据此给那人的在线弹幕连接即时推一帧提示。
 * ========================================================================== */

/**
 * 我的禁言名单（按禁言时刻倒序，已过期的不返回）。
 * @returns {Promise<Array<{ userId: string, nickname: string, avatar: string, expireAt: number, permanent: boolean, mutedAt: number }>>}
 */
export function fetchMuteList() {
  return http.get('/live/moderation/mutes', { silent: true })
}

/**
 * 禁言某人。
 * @param {{ userId: string, minutes?: number, roomId?: string }} body minutes 为 0/负数即永久
 */
export function muteUser(body) {
  return http.post('/live/moderation/mute', body)
}

/** 解除禁言（幂等） */
export function unmuteUser(userId, roomId) {
  return http.delete(`/live/moderation/mute/${userId}`, { params: roomId ? { roomId } : {} })
}

/** 我的弹幕屏蔽词列表（服务端已折叠为小写去空白的形式） */
export function fetchBlockWords() {
  return http.get('/live/moderation/words', { silent: true })
}

/** 整表替换屏蔽词：传空数组即清空，返回清洗后的生效清单 */
export function replaceBlockWords(words) {
  return http.put('/live/moderation/words', { words })
}

/* ============================================================================
 * 连麦 PK（两个正在直播的房间比热度）
 *
 * 只有「人要主动点的那几下」走这里：发起、投票、提前结束。进度与倒计时不轮询，
 * 由服务端每秒经 /ws/live 推一帧 pk（见 Live.vue 的 onPkFrame）。
 * 对手必须也在直播中（服务端会拒），所以发起入口只在主播侧，且候选来自直播中列表。
 * ========================================================================== */

/**
 * 发起 PK。
 * @param {{ targetUserId: string, durationSeconds?: number }} body 时长不传用服务端默认值，超上限按上限取
 * @returns {Promise<object>} 开场快照，字段与 ws 的 pk 帧一致（无 phase）
 */
export function startPk(body) {
  return http.post('/live/pk/start', body)
}

/**
 * 投票站队：一人一票，改票即覆盖（服务端按 userId 写 Hash field）。
 * @param {string} pkId @param {'A'|'B'} side 会话里的 A/B，不是「我方/对方」
 */
export function votePk(pkId, side) {
  return http.post(`/live/pk/${pkId}/vote`, {}, { params: { side } })
}

/** 提前结束：只有对阵双方的主播能喊停，按当时热度定胜负 */
export function stopPk(pkId) {
  return http.post(`/live/pk/${pkId}/stop`)
}

/** 房间当前 PK 快照：WS 补帧是主路径，这个接口用于弹幕通道还没连上时兜底 */
export function fetchCurrentPk(roomId) {
  return http.get('/live/pk/current', { params: { roomId }, silent: true })
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
