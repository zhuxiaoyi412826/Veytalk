import http from './request'

export function fetchProfile() {
  return http.get('/user/profile')
}

export function updateProfile(data) {
  return http.put('/user/profile', data)
}

export function changePassword(data) {
  return http.put('/user/password', data)
}

/**
 * 绑定 / 换绑手机号：{ phone, smsCode }。
 *
 * 走的是已登录本人的操作，后端 scene=bind 发短信时不需要图形验证码，
 * 因此这里只传手机号与短信验证码。成功后返回最新的个人资料。
 */
export function bindPhone(data) {
  return http.put('/user/phone', data)
}

/** 他人资料卡片，含 friend / blocked / blockedByOther 三个关系位 */
export function fetchUserCard(id) {
  return http.get(`/user/${id}/card`)
}

/**
 * 用户搜索。
 *
 * exact=true 时按账号或手机号精确匹配（加好友场景），false 时按关键词模糊搜索昵称/账号。
 */
export function searchUsers(params) {
  return http.get('/user/search', { params })
}

/**
 * 批量在线状态。
 *
 * 返回的是 Map<Long, Boolean>，JSON 里键一律是字符串，取值时要用 asId 归一化后再查。
 */
export function fetchOnlineStatus(userIds) {
  return http.get('/user/online', { params: { userIds } })
}
