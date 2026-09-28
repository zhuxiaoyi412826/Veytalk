import http from './request'
import { getToken, getTokenName } from '@/utils/token'
import { apiBaseURL } from '@/utils/env'

/**
 * AI 相关接口：面试官对话 + 全网检索（消息搜索框的「网络」分组）+ 面试监考上报。
 *
 * /chat 是 SSE 流式接口，不能走 axios（它要等整个响应体收完才回调），
 * 也不能走 EventSource（只支持 GET、不能带自定义请求头），
 * 所以用 fetch + ReadableStream 手工解析 SSE：
 * 后端事件共三种 —— delta（增量文本 JSON）、done（本轮结束）、error（失败原因 JSON）。
 */

/** 面试官状态：知识库目录/文件数/片段数、模型名、API Key 是否已配置 */
export function fetchInterviewStatus() {
  return http.get('/ai/interview/status', { silent: true })
}

/**
 * 全网关键字检索，供首页消息搜索框的「网络」分组使用。
 *
 * 与聊天消息检索是两个并行请求：本地结果不等外网，外网慢几秒也只空着自己那一栏。
 * 后端把抓取失败、被限流、结果页改版都收敛成空 results，
 * 所以这里 silent：不能让外网抖动弹一个全局 toast 打断本地搜索。
 */
export function searchWeb(keyword) {
  return http.get('/ai/search/web', { params: { keyword }, silent: true })
}

/**
 * 发起一轮流式面试对话。
 *
 * @param {Array<{role:string,content:string}>} messages 完整对话历史（空数组=开始新面试）
 * @param {object} options
 * @param {(delta:string)=>void} options.onDelta 每收到一段增量文本回调一次
 * @param {AbortSignal} [options.signal] 用于「停止生成」
 * @returns {Promise<void>} done 事件后 resolve；error 事件/传输失败 reject
 */
export async function streamInterviewChat(messages, { onDelta, signal } = {}) {
  const response = await fetch(`${apiBaseURL()}/ai/interview/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [getTokenName()]: getToken() || ''
    },
    body: JSON.stringify({ messages }),
    signal
  })

  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    // 进入 SSE 之前就失败了（未登录/限流/参数错误），后端按统一的 Result JSON 返回
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message || `请求失败（${body.code ?? response.status}）`)
  }
  if (!response.ok || !response.body) {
    throw new Error(`AI 服务异常（HTTP ${response.status}）`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  // 解析一个完整的 SSE 事件块；返回 true 表示收到 done，应当结束读取
  function handleEvent(raw) {
    let event = 'message'
    const dataLines = []
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }
    const data = dataLines.join('\n')
    if (!data) {
      return false
    }
    if (event === 'delta') {
      const payload = JSON.parse(data)
      if (payload.content) {
        onDelta && onDelta(payload.content)
      }
    } else if (event === 'error') {
      const payload = JSON.parse(data)
      throw new Error(payload.message || 'AI 服务异常')
    }
    return event === 'done'
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      if (!rawEvent.trim()) {
        continue
      }
      if (handleEvent(rawEvent)) {
        reader.cancel().catch(() => {})
        return
      }
    }
  }
}

/* ==================== 面试监考与审计 ==================== */

/**
 * 开始面试：建一条审计会话。
 *
 * 拿到 sessionId 后，本轮面试的所有事件上报与问答落库都要带上它。
 * screen 是客户端分辨率，用于事后判断「切屏误报」（分屏/双显场景误报率明显高）。
 */
export function startInterviewSession({ title, screen } = {}) {
  return http.post('/ai/interview/session/start', null, { params: { title, screen }, silent: true })
}

/**
 * 批量上报监考事件。返回最新计数与 reachedLimit / ended。
 *
 * 失败时不弹全局 toast：监考上报是后台行为，网络抖动不该打断候选人答题，
 * 丢的那一批由前端回放进队列下次重试。
 */
export function reportInterviewEvents(sessionId, events) {
  return http.post('/ai/interview/events', { sessionId, events }, { silent: true })
}

/**
 * 落一轮问答（上一答 + 本新题）。
 *
 * payload.turnNo 是幂等键的一部分：服务端拿「第几题」而不是自算的 seq 去撞唯一键，
 * 网络重试才不会把一轮问答存成两轮。作答行归题号减一（候选人答的是上一题）。
 */
export function saveInterviewTurn(payload) {
  return http.post('/ai/interview/turn', payload, { silent: true })
}

/** 结束面试 */
export function endInterviewSession(sessionId, reason) {
  return http.post('/ai/interview/session/end', { sessionId, reason }, { silent: true })
}

/**
 * 页面正在被卸载（刷新 / 关标签页）时的收尾上报。
 *
 * 这时候 axios 的请求会被浏览器直接掐掉，所以必须用 fetch 的 keepalive：
 * 浏览器会在文档卸载后继续把这几个小请求发完。sendBeacon 更稳但带不了
 * 鉴权头，本项目 token 在 header 里，因此选 keepalive。
 *
 * endReason 传 null 表示只带走事件、不下结论：刷新后要不要记「未完成」
 * 由新页面根据 sessionStorage 的残留标记判断，关页则交给服务端在下次
 * 开始时补记。不包错误处理：页面都要走了，报错也没人看。
 */
export function interviewLeaveBeacon(sessionId, events = [], endReason = null) {
  if (!sessionId) {
    return
  }
  const headers = {
    'Content-Type': 'application/json',
    [getTokenName()]: getToken() || ''
  }
  const base = events.length
    ? fetch(`${apiBaseURL()}/ai/interview/events`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ sessionId, events }),
      keepalive: true
    }).catch(() => {})
    : Promise.resolve()
  if (!endReason) {
    return
  }
  base.then(() => fetch(`${apiBaseURL()}/ai/interview/session/end`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ sessionId, reason: endReason }),
    keepalive: true
  })).catch(() => {})
}
