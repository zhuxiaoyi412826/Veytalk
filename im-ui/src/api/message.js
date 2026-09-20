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
 * 后端限制发出后撤回时限内（im.message.recall-limit-seconds，默认 2 小时）、且只有发送者本人（或持 message:recall:any 权限者）可撤。
 */
export function recallMessage(messageId) {
  return http.put(`/message/${messageId}/recall`)
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
