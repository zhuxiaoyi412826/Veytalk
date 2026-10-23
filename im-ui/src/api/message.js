import http from './request'

/**
 * 发送消息（HTTP 通道）。
 *
 * 与 WebSocket 的 chat 上行等价，走 HTTP 是为了让「发送中 / 失败重试」有明确的 Promise 语义。
 * 会话定位优先级：conversationId > toUserId > toGroupId。
 *
 * 附件类消息（msgType 2/3/4）把文件 ID 放在 content 即可，服务端的
 * AttachmentContentHandler 会按文件记录回填 fileName / fileSize / contentType / ext /
 * duration / fileUrl，客户端上报的同名字段一律被覆盖（防止把别人的 fileId 填进来越权）。
 * 但 width / height 不在回填范围内，只有客户端知道图片尺寸，所以这两个值要自己传，
 * 否则气泡里的缩略图只能靠加载完后重算，列表滚动时会跳动。
 */
export function sendMessage(data) {
  // silent：失败不走全局 toast，由调用方在气泡上标红叹号 + 离线队列自动重发；
  // 业务类拒绝（敏感词/限流）的提示文案由 ChatWindow 的 catch 统一补弹
  return http.post('/message/send', data, { silent: true })
}

/**
 * 历史消息，按 seq 升序返回。
 *
 * 首次进入会话不传 beforeSeq 取最新一页；向上滚动加载更早消息时，
 * 用当前已加载的第一条的 seq 作为游标。返回条数少于 size 说明已经到顶。
 */
export function fetchHistory(conversationId, beforeSeq, size = 20) {
  const params = { conversationId, size }
  if (beforeSeq !== null && beforeSeq !== undefined && beforeSeq !== '') {
    params.beforeSeq = beforeSeq
  }
  return http.get('/message/history', { params })
}

/**
 * 导出某会话的完整聊天记录（服务端权威数据，非本地缓存）。
 *
 * 一次性返回该会话全部消息（按 seq 正序，已排除本人单端删除），单次上限 20000 条，
 * 仅会话成员可导。前端拿到后拼装为 JSON/CSV 下载到本机磁盘，用于合规备份与迁移。
 */
export function exportConversationMessages(conversationId) {
  return http.get('/message/export', { params: { conversationId } })
}

/**
 * 离线消息。
 *
 * 返回上次确认位点之后的全部消息（单次最多 500 条），拉取后后端自动推进位点，
 * 但不清未读数 —— 未读要等用户真正打开会话才消失，所以这里拉完还得走 markRead。
 */
export function fetchOfflineMessages() {
  return http.get('/message/offline')
}

/** 把全部会话的确认位点推到最新，丢弃尚未拉取的离线消息。正常重连不要用 */
export function clearOffline() {
  return http.put('/message/offline/clear')
}

/** 上报已读，maxSeq 为空表示整个会话全部已读 */
export function reportRead(conversationId, maxSeq) {
  const body = { conversationId }
  if (maxSeq !== null && maxSeq !== undefined && maxSeq !== '') {
    body.maxSeq = maxSeq
  }
  return http.put('/message/read', body, { silent: true })
}

/** 上报已送达，空列表后端会静默忽略 */
export function reportDelivered(messageIds) {
  return http.post('/message/delivered', { messageIds }, { silent: true })
}

/**
 * 撤回消息。
 * 后端限制发出后撤回时限内（im.message.recall-limit-seconds，默认 2 分钟）、且只有发送者本人（或持 message:recall:any 权限者）可撤；超出回 MESSAGE_RECALL_TIMEOUT。
 */
export function recallMessage(messageId) {
  return http.put(`/message/${messageId}/recall`)
}

/**
 * 编辑消息。
 * 后端限制：仅发送者本人、时限内（复用 im.message.recall-limit-seconds）、且未撤回的文本消息可编辑；
 * 新正文重新过 XSS 清洗与敏感词遮蔽。失败原因（超时限 / 无权 / 内容不合法）由全局 toast 弹出。
 */
export function editMessage(messageId, content) {
  return http.put(`/message/${messageId}/edit`, { content })
}

/**
 * 表情回应。
 * 对一条消息贴（remove=false）或取消（remove=true）一个表情，所有消息类型适用、已撤回不可；
 * ★ 一人对一条消息只保留最后一个表情（换表情会顶掉旧的），重复点同一个服务端幂等。
 * 回包带最新权威聚合视图，store 直接用它覆盖本端；其他端靠 reaction-notify 增量同步。
 */
export function reactMessage(messageId, emoji, remove) {
  return http.post(`/message/${messageId}/reaction`, { emoji, remove })
}

/**
 * 投票：选若干选项（整体替换旧票，空数组表示撤回本人投票）；close=true 由发起人截止。
 */
export function voteMessage(messageId, options, close) {
  return http.post(`/message/${messageId}/vote`, { options, close })
}

/**
 * 接龙：向一条接龙消息追加一个条目（text 为空时服务端回落为本人昵称）；close=true 由发起人截止。
 * 返回含最新条目列表的消息视图（extra.chainItems）。
 */
export function chainMessage(messageId, text, close) {
  return http.post(`/message/${messageId}/chain`, { text, close })
}

/**
 * 语音转文字（依赖服务端外部 ASR，未接入时降级为错误提示）。
 *
 * 仅对未撤回的语音消息可用；返回识别文本（仅展示不落库）。失败原因（未配置/不支持）
 * 由全局 toast 弹出，调用方 silent 以免双重弹框。
 */
export function toMessageText(messageId) {
  return http.post(`/message/${messageId}/to-text`, null, { silent: true })
}

/**
 * 消息翻译（依赖服务端外部翻译服务，未接入时降级为错误提示）。
 *
 * 仅对未撤回的文本消息可用；lang 为空时后端取配置默认目标语言。返回译文仅展示，不改写原消息。
 */
export function translateMessage(messageId, lang) {
  return http.post(`/message/${messageId}/translate`, null, { params: { lang }, silent: true })
}

/**
 * 群文件 / 群相册：按群会话 + 消息类型分页取沉淀的附件消息。
 * msgType 2=群相册（图片）、3=群文件；返回 PageResult<MessageVO>。
 */
export function fetchGroupMedia(conversationId, msgType, current = 1, size = 20) {
  return http.get('/message/group-media', { params: { conversationId, msgType, current, size } })
}

/** 群精华消息列表：按群会话分页取被标记为精华的消息，返回 PageResult<MessageVO>。 */
export function fetchGroupEssence(conversationId, current = 1, size = 20) {
  return http.get('/message/group-essence', { params: { conversationId, current, size } })
}

/**
 * 标记 / 取消精华消息：仅群聊、仅群主与管理员可操作；essence=true 设为精华、false 取消。
 * 返回带最新 essence 标记的消息 VO；其他端由 essence-notify 回执刷新。
 */
export function markMessageEssence(messageId, essence) {
  return http.put(`/message/${messageId}/essence`, { essence })
}

/**
 * 我的收藏列表：按收藏时间倒序分页，conversationId 为空则跨全部会话。返回 PageResult<MessageVO>。
 */
export function fetchFavorites({ conversationId, current = 1, size = 20 } = {}) {
  return http.get('/message/favorites', {
    params: { conversationId, current, size },
    silent: true
  })
}

/**
 * 标记 / 取消收藏：用户维度的私人行为，单聊群聊都可收、无需管理权限。
 * favorite=true 收藏、false 取消；返回带最新 favorite 标记的消息 VO，其余端由 favorite-notify 回执刷新。
 */
export function markMessageFavorite(messageId, favorite) {
  return http.put(`/message/${messageId}/favorite`, { favorite })
}

/** 单端删除：只对自己不可见，对方与会话记录都保留 */
export function deleteMessage(messageId) {
  return http.delete(`/message/${messageId}`)
}

/** 清空某会话的全部聊天记录（单端）：只对自己生效，对方不受影响 */
export function clearConversationMessages(conversationId) {
  return http.delete(`/message/clear/${conversationId}`)
}

/** 会话内消息内容检索 */
export function searchMessages(params) {
  return http.get('/message/search', { params })
}

/**
 * 上报闪照已被查看。
 *
 * 服务端收到即把 extra.flashViewed 置真并逻辑删除原图文件记录：
 * 必须在图片字节已经取回本地（blob）之后再调，否则还没渲染就永久 404。
 * 返回查看者视角的最新消息 VO，用它就地刷新气泡状态。
 */
export function flashViewMessage(messageId) {
  return http.post(`/message/${messageId}/flash-view`, null, { silent: true })
}

/**
 * 转发消息。
 *
 * 把一条已存在的消息复制到目标会话，附件复用原文件不重新上传。
 * 目标会话定位优先级：conversationId > toUserId > toGroupId。
 * 转发不携带引用，收到的消息就是一条普通的新消息。
 */
export function forwardMessage(data) {
  return http.post('/message/forward', data)
}

/**
 * 服务端全局敏感词过滤开关（高级设置-调试入口）。
 *
 * 状态是后端内存里的：关闭后全服文本与文件名都停止遮蔽，立即生效不需重启；
 * 重启后回到配置文件 im.message.sensitive-filter-enabled 的值。
 */
export function fetchSensitiveFilter() {
  return http.get('/message/sensitive-filter', { silent: true })
}

export function setSensitiveFilter(enabled) {
  return http.put('/message/sensitive-filter', null, { params: { enabled }, silent: true })
}
