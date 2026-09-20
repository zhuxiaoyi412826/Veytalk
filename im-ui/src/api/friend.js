import http from './request'

/** 好友列表，keyword 为空时返回全部；displayName 已经是「备注优先，其次昵称」的结果 */
export function fetchFriends(keyword) {
  return http.get('/friend/list', { params: keyword ? { keyword } : {} })
}

/** 我设置过的好友分组名 */
export function fetchFriendGroups() {
  return http.get('/friend/groups')
}

/**
 * 单个好友的详情，字段与列表项一致。
 *
 * 后端要求关系必须存在，否则直接抛错；资料卡页面在「关系已被对方删除」时会调到这里，
 * 所以留出 options 让调用方传 { silent: true } 自行降级，不要弹一条用户看不懂的错误提示。
 */
export function fetchFriendCard(friendId, options) {
  return http.get(`/friend/${friendId}/card`, options)
}

export function updateRemark(friendId, remark) {
  return http.put(`/friend/${friendId}/remark`, { remark })
}

export function updateGroup(friendId, groupName) {
  return http.put(`/friend/${friendId}/group`, { groupName })
}

export function removeFriend(friendId) {
  return http.delete(`/friend/${friendId}`)
}

export function blockFriend(friendId) {
  return http.put(`/friend/${friendId}/block`)
}

export function unblockFriend(friendId) {
  return http.delete(`/friend/${friendId}/block`)
}

/** 我拉黑的用户列表（黑名单），服务端存储所以多端一致 */
export function fetchBlacklist() {
  return http.get('/friend/blacklist')
}

/**
 * 发起好友申请。
 * targetUserId 与 targetAccount 二选一：从资料卡进来用前者，从搜索框输入账号用后者。
 */
export function applyFriend(data) {
  return http.post('/friend/request/apply', data)
}

/** 收到的申请，status 缺省时后端返回全部状态 */
export function fetchReceivedRequests(params) {
  return http.get('/friend/request/received', { params })
}

/** 我发出的申请 */
export function fetchSentRequests(params) {
  return http.get('/friend/request/sent', { params })
}

/** 待处理申请数，用于导航红点 */
export function fetchPendingCount() {
  return http.get('/friend/request/pending-count', { silent: true })
}

/** 同意申请，返回值是新建的单聊会话 ID，可直接跳转聊天窗口 */
export function acceptRequest(id) {
  return http.put(`/friend/request/${id}/accept`)
}

export function rejectRequest(id) {
  return http.put(`/friend/request/${id}/reject`)
}
