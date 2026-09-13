import http from './request'

/** 会话列表，后端已按「置顶优先 + 最后消息时间倒序」排好，前端不要再排一次 */
export function fetchConversations() {
  return http.get('/conversation/list')
}

export function fetchConversation(id) {
  return http.get(`/conversation/${id}`)
}

/**
 * 打开与某人的单聊会话，已存在则直接返回原会话 ID。
 * 幂等，所以「发起聊天」按钮不需要先判断是否已有会话。
 */
export function createSingleConversation(targetUserId) {
  return http.post('/conversation/single', { targetUserId })
}

/** 上报已读位置，lastAckSeq 为当前会话里已读到的最大 seq */
export function markConversationRead(id, lastAckSeq) {
  return http.put(`/conversation/${id}/read`, { lastAckSeq }, { silent: true })
}

export function setConversationTop(id, enabled) {
  return http.put(`/conversation/${id}/top`, { enabled })
}

export function setConversationMute(id, enabled) {
  return http.put(`/conversation/${id}/mute`, { enabled })
}

/**
 * 隐藏 / 恢复会话。
 *
 * 后端的 ConversationMember.hidden 映射到列 is_deleted，列表查询带 is_deleted = 0，
 * 所以隐藏后会话直接从列表里消失，但消息与会话主体都保留。
 * 隐藏期间对方发的消息仍会在下次上线时补齐，且新消息到达时后端会自动把会话重新露出。
 */
export function hideConversation(id, enabled) {
  return http.put(`/conversation/${id}/hide`, { enabled })
}

/** 删除会话：后端实现上等价于 hideConversation(id, true)，只是返回的提示文案不同 */
export function removeConversation(id) {
  return http.delete(`/conversation/${id}`)
}

/** 全部会话的未读总数，用于导航角标与浏览器标题 */
export function fetchTotalUnread() {
  return http.get('/conversation/unread/total', { silent: true })
}
