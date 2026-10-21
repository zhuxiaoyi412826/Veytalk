import http from './request'

/**
 * 管理后台的接口封装：看板、用户管理、举报工单、角色分配，以及三期的
 * 视频管理 / 直播管理 / 监控告警 / 日志管理。
 *
 * 形状对齐 im-admin 的各 Controller，一处不改：
 *  - /api/admin/dashboard      GET，refresh 为 query 参数
 *  - /api/admin/user/page      GET，入参 keyword / status / current / size
 *  - /api/admin/user/{id}      GET
 *  - /api/admin/user/{id}/disable  POST，reason 是 <b>@RequestParam 而不是 JSON body</b>
 *  - /api/admin/user/{id}/enable   POST
 *  - /api/admin/report/page    GET，入参 status / targetType / reasonCode / keyword / current / size
 *  - /api/admin/report/{id}    GET
 *  - /api/admin/report/{id}/accept   POST，无 body
 *  - /api/admin/report/{id}/reject   POST，body { remark }
 *  - /api/admin/report/{id}/handle   POST，body { action, remark }
 *  - /api/report/config        GET，字典（后台侧同样能读，不为后台另开一份）
 *
 * 三期新增（同样一处不改）：
 *  - /api/admin/video/page     GET，入参 keyword / status / uploaderId / current / size
 *  - /api/admin/video/{id}/offline  POST，reason 是 @RequestParam（与 disable 同一口径）
 *  - /api/admin/video/{id}/online   POST，无 body
 *  - /api/admin/video/{id}          DELETE，无 body
 *  - /api/admin/live/page    GET，入参 keyword / status / anchorId / current / size
 *  - /api/admin/live/{roomId}       GET
 *  - /api/admin/live/{roomId}/stop  POST，无 body（原因服务端固定为 ban）
 *  - /api/admin/monitor/overview    GET；/api/admin/monitor/slow  GET
 *  - /api/admin/log/files           GET；/api/admin/log/content  GET，key / tail / keyword
 *
 * disable 的 reason 用 params 而不是 data 传：后端没写 @RequestBody，
 * 发一个 JSON body 过去它会当成「reason 为空」而打回「请填写禁用事由」，
 * 而前端看到的是一句看不出问题的 400 提示。
 */

/** 看板数据；refresh=true 会绕过服务端 60s 缓存真跑一遍聚合，只能人手点，不做定时轮询 */
export function fetchDashboard(refresh = false) {
  return http.get('/admin/dashboard', { params: { refresh } })
}

export function fetchUserPage(params) {
  return http.get('/admin/user/page', { params })
}

export function fetchUserDetail(userId) {
  return http.get(`/admin/user/${userId}`)
}

/** 禁用账号，必须写事由（服务端校验），后端会顺手踢掉该用户的全部长连接 */
export function disableUser(userId, reason) {
  return http.post(`/admin/user/${userId}/disable`, null, { params: { reason } })
}

export function enableUser(userId) {
  return http.post(`/admin/user/${userId}/enable`)
}

export function fetchReportPage(params) {
  return http.get('/admin/report/page', { params })
}

export function fetchReportDetail(id) {
  return http.get(`/admin/report/${id}`)
}

/** 待处理 → 已受理，纯人工推进，不写处理时间 */
export function acceptReport(id) {
  return http.post(`/admin/report/${id}/accept`)
}

/** 判定举报不成立：只改工单状态，不动被举报对象 */
export function rejectReport(id, remark) {
  return http.post(`/admin/report/${id}/reject`, { remark })
}

/** 执行处置动作并置为已处置；动作由对象归属模块执行，失败时工单停在原状态 */
export function handleReport(id, action, remark) {
  return http.post(`/admin/report/${id}/handle`, { action, remark })
}

/** 举报字典：状态/理由/对象类型/处置动作的中文名都从这里出，前端不复制一份码表 */
export function fetchReportConfig() {
  return http.get('/report/config')
}

/* ==================== 角色分配（二期） ==================== */

/** 可分配的角色列表（启用且未删除），给「配角色」下拉与角色目录页用 */
export function fetchRoles() {
  return http.get('/admin/role/list')
}

/** 某用户当前持有的角色对象列表，弹框据此勾选复选框 */
export function fetchUserRoles(userId) {
  return http.get(`/admin/role/user/${userId}`)
}

/**
 * 整体重设用户角色：roleIds 就是该用户最终应持有的角色，空数组=清空全部后台角色。
 * 后端会拒三种调用：改自己、摘掉最后一个可用管理员、包含未知/停用角色。
 */
export function assignUserRoles(userId, roleIds) {
  return http.put(`/admin/role/user/${userId}`, { roleIds })
}

/* ==================== 视频管理（三期） ==================== */

/** 视频列表：含已下架（status=2），删除后的行整体隐身；固定按发布时间倒序 */
export function fetchVideoPage(params) {
  return http.get('/admin/video/page', { params })
}

/** 下架视频：reason 走 query 参数进服务端审计日志，与 disableUser 同一口径 */
export function offlineVideo(id, reason) {
  return http.post(`/admin/video/${id}/offline`, null, { params: { reason } })
}

/** 恢复上架，幂等 */
export function onlineVideo(id) {
  return http.post(`/admin/video/${id}/online`)
}

/** 逻辑删除，不可自助恢复——前端必须二次确认 */
export function deleteVideo(id) {
  return http.delete(`/admin/video/${id}`)
}

/* ==================== 直播管理（三期） ==================== */

/** 场次列表：含全部历史与已封禁场次；直播中恒排最前，响应绝不含 streamKey */
export function fetchLivePage(params) {
  return http.get('/admin/live/page', { params })
}

export function fetchLiveDetail(roomId) {
  return http.get(`/admin/live/${roomId}`)
}

/**
 * 强制关播（ban）：终态，没有解封接口。对已结束的场次幂等跳过，
 * 响应带回处置后的最新档案，前端就地刷那一行。
 */
export function stopLive(roomId) {
  return http.post(`/admin/live/${roomId}/stop`)
}

/* ==================== 监控告警（三期） ==================== */

/** 监控总览：JVM/HTTP/连接池/健康/慢接口一屏取数，取不到的指标为 null（显示「—」） */
export function fetchMonitorOverview() {
  return http.get('/admin/monitor/overview')
}

/** 只回慢接口分组，比整屏总览便宜，供想高频刷新的场景 */
export function fetchMonitorSlow() {
  return http.get('/admin/monitor/slow')
}

/* ==================== 日志管理（三期） ==================== */

/** 七个逻辑档的存在性、大小与最后修改时间；前端只认 key，永远不传路径 */
export function fetchLogFiles() {
  return http.get('/admin/log/files')
}

/** 尾读日志：tail 行服务端夹上限，关键词只在尾读窗口内过滤（不是全文件搜索） */
export function fetchLogContent(key, tail, keyword) {
  return http.get('/admin/log/content', { params: { key, tail, keyword: keyword || undefined } })
}
