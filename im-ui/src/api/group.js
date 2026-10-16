import http from './request'

/**
 * 我的群聊列表，按入群时间倒序。
 * 每项含 groupId / name / avatar / memberCount / myRole / conversationId 等。
 */
export function fetchMyGroups() {
  return http.get('/group/my')
}

/** 群详情，非成员也能看群名与公告，但 myRole 等视角字段为空 */
export function fetchGroupDetail(id) {
  return http.get(`/group/${id}`)
}

/**
 * 创建群组。
 * 创建者自动成为群主；memberIds 为初始成员列表，可为空。
 * 返回值含 conversationId，可直接跳转群聊窗口。
 */
export function createGroup(data) {
  return http.post('/group/create', data)
}

/** 修改群资料（群名 / 公告 / 头像），字段传 null 表示不改，传空串表示清空 */
export function updateGroup(id, data) {
  return http.put(`/group/${id}`, data)
}

/** 解散群聊，仅群主可操作 */
export function dismissGroup(id) {
  return http.delete(`/group/${id}`)
}

/** 邀请成员入群，已在群内的会被跳过，返回实际新增的用户 ID 列表 */
export function addMembers(id, userIds) {
  return http.post(`/group/${id}/members`, { userIds })
}

/** 群成员分页列表，群主与管理员排在前面 */
export function fetchMembers(id, current = 1, size = 50) {
  return http.get(`/group/${id}/members`, { params: { current, size } })
}

/** 移除群成员，群主可移除任何人，管理员只能移除普通成员 */
export function removeMember(id, userId) {
  return http.delete(`/group/${id}/members/${userId}`)
}

/** 设置成员角色（管理员 / 普通成员），仅群主可操作 */
export function updateRole(id, userId, role) {
  return http.put(`/group/${id}/members/${userId}/role`, { role })
}

/**
 * 单人禁言。
 * muted=true 时 minutes 为空表示无限期；muted=false 时解除禁言。
 */
export function muteMember(id, userId, muted, minutes) {
  const body = { muted }
  if (muted && minutes !== undefined && minutes !== null) {
    body.minutes = minutes
  }
  return http.put(`/group/${id}/members/${userId}/mute`, body)
}

/** 退出群聊，群主需先转让 */
export function quitGroup(id) {
  return http.post(`/group/${id}/quit`)
}

/** 全员禁言开关，管理员以上可操作且自身不受影响 */
export function toggleMuteAll(id, muteAll) {
  return http.put(`/group/${id}/mute-all`, { muteAll })
}

/** 转让群主，仅群主可操作，原群主降级为普通成员 */
export function transferGroup(id, userId) {
  return http.put(`/group/${id}/transfer/${userId}`)
}

/** 修改我的群昵称，留空表示清除，回退展示账号昵称 */
export function updateMyNickname(id, nickname) {
  return http.put(`/group/${id}/my-nickname`, { nicknameInGroup: nickname })
}

/* ==================== 入群审批 / 邀请链接 / 群二维码 ==================== */

/** 开关入群审批，群主与管理员可操作；返回更新后的群详情 */
export function setJoinApproval(id, joinApproval) {
  return http.put(`/group/${id}/join-approval`, { joinApproval })
}

/**
 * 获取群邀请信息（邀请码 + 邀请链接 + 二维码 base64），群主与管理员可拉。
 * base 传前端当前站点根地址（window.location.origin），后端据此拼深度链接。
 */
export function fetchGroupInvite(id, base) {
  return http.get(`/group/${id}/invite`, { params: { base } })
}

/**
 * 凭邀请码申请入群。
 * 目标群未开审批时直接入群（status=joined），开了审批则提交待管理员确认（status=pending）。
 */
export function applyJoin(data) {
  return http.post('/group/join', data)
}

/** 入群申请分页列表，仅管理层可看 */
export function fetchJoinRequests(id, current = 1, size = 20) {
  return http.get(`/group/${id}/join-requests`, { params: { current, size } })
}

/** 审批入群申请，approve=true 通过（申请人立即入群）、false 拒绝 */
export function handleJoinRequest(requestId, approve) {
  return http.put(`/group/join-request/${requestId}/handle`, { approve })
}
