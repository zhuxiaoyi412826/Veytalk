import http from './request'
import { getToken, getTokenName } from '@/utils/token'
import { apiBaseURL } from '@/utils/env'

/**
 * AI 面试官接口。
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
