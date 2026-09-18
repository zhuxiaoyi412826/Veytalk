import { reactive } from 'vue'
import { fetchWsTicket } from '@/api/ws'
import { getToken, getDeviceId } from '@/utils/token'
import { dlog } from '@/utils/logger'
import { wsBaseURL } from '@/utils/env'

/**
 * WebSocket 客户端：连接、心跳、指数退避重连与事件分发。
 *
 * 事件名直接用后端 WsMessageType 的取值（message / ack / read-notify / recall-notify /
 * notify / kickout / online-state / unread / delivered-notify / error / pong），
 * 不做二次映射 —— 中间加一层翻译只会让人对着后端日志排查时还要先反查一次表。
 * 额外提供两个非协议事件：'open'（含重连成功）与 'close'。
 */

/** 后端 im.websocket.heartbeat-interval-seconds 的默认值，握手的欢迎帧里会带真值 */
const DEFAULT_HEARTBEAT_SECONDS = 30

/** 退避上限：再往上加意义不大，用户已经在等，30 秒一次足够体现「还在试」 */
const MAX_BACKOFF_MS = 30000

/** 登录态失效的错误码，命中后不该再重连（重连只是把 401 刷屏） */
const CODE_UNAUTHORIZED = 1002
const CODE_KICKED_OUT = 2010

export const socketState = reactive({
  /** idle | connecting | open | reconnecting | closed */
  status: 'idle',
  /** 最近一次失败原因，用于界面上的连接状态提示 */
  lastError: ''
})

const handlers = new Map()

let ws = null
let heartbeatTimer = null
let pongTimer = null
let reconnectTimer = null
let attempts = 0
let heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS
/** 本次连接是否成功握手过，用来区分「握手被拒」与「连上后掉线」 */
let everOpened = false
/**
 * 主动关闭标记。
 *
 * 三种情况会置位：调用 disconnect()（登出）、收到 kickout（服务端马上要关连接）、
 * 换票据时被判定登录态失效。置位后 onclose 不再触发重连 ——
 * 服务端明确表达的「我不再接受这个连接」如果被当成网络抖动重试，
 * 就会变成无限重连风暴。
 */
let manualClose = false
/** 已经成功重连过几次，'open' 事件据此告诉调用方要不要补拉离线消息 */
let reconnectCount = 0

function emit(type, payload) {
  const set = handlers.get(type)
  if (!set || set.size === 0) {
    return
  }
  // 复制一份再遍历：处理器里调用 off 是常见写法（一次性监听），直接遍历原集合会漏掉后续项
  Array.from(set).forEach((handler) => {
    try {
      handler(payload)
    } catch (error) {
      // 一个处理器抛异常不能影响同类型的其他处理器，更不能冒泡到 ws.onmessage
      // 否则一次渲染 bug 会让整条连接的后续报文全部丢失
      console.error(`[ws] 事件 ${type} 的处理器执行失败`, error)
    }
  })
}

export function on(type, handler) {
  if (!handlers.has(type)) {
    handlers.set(type, new Set())
  }
  handlers.get(type).add(handler)
  // 返回注销函数，配合 onUnmounted 一行搞定
  return () => off(type, handler)
}

export function off(type, handler) {
  const set = handlers.get(type)
  if (set) {
    set.delete(handler)
  }
}

function wsUrl(endpoint, ticket) {
  // Electron 桌面端连远程后端（wsBaseURL 已把 http 换成 ws）；Web 部署按同源 location.host 推导，
  // 开发期走 vite 的 /ws 代理、生产走同域反代，两种情况都不需要改代码（见 utils/env.js）
  const path = endpoint && endpoint.startsWith('/') ? endpoint : '/ws'
  return `${wsBaseURL()}${path}?ticket=${encodeURIComponent(ticket)}`
}

function clearTimers() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
  if (pongTimer) {
    clearTimeout(pongTimer)
    pongTimer = null
  }
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function startHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
  }
  if (pongTimer) {
    clearTimeout(pongTimer)
  }
  heartbeatTimer = setInterval(() => {
    if (!isOpen()) {
      return
    }
    send('ping')
    // 两个心跳周期内没等到 pong 就认为链路已死。
    // 必须短于服务端的 heartbeat-timeout-seconds（90s），
    // 否则会出现「客户端还以为连着、服务端已经把会话清了」的空窗，
    // 表现为消息发出去了但对方收不到，且界面毫无异常。
    pongTimer = setTimeout(() => {
      console.warn('[ws] 心跳超时，主动断开以触发重连')
      if (ws) {
        ws.close()
      }
    }, heartbeatSeconds * 2000)
  }, heartbeatSeconds * 1000)
}

export function isOpen() {
  return !!ws && ws.readyState === WebSocket.OPEN
}

/**
 * 发送一帧。
 *
 * @returns 连接未就绪时返回 false，调用方据此回退到 HTTP 接口。
 *          刻意不排队：队列会在断线期间无限增长，而这里的上行报文
 *          （送达/已读上报）全都有等价的 HTTP 接口，回退比缓存更可靠。
 */
export function send(type, data, clientMsgId) {
  if (!isOpen()) {
    return false
  }
  const packet = { type, timestamp: Date.now() }
  if (clientMsgId) {
    packet.clientMsgId = clientMsgId
  }
  if (data !== undefined && data !== null) {
    packet.data = data
  }
  try {
    ws.send(JSON.stringify(packet))
    dlog('ws send ↑', type, packet)
    return true
  } catch (error) {
    console.error('[ws] 发送失败', type, error)
    return false
  }
}

async function openOnce() {
  if (!getToken()) {
    socketState.status = 'closed'
    return
  }
  socketState.status = attempts === 0 ? 'connecting' : 'reconnecting'
  everOpened = false

  let ticketVo
  try {
    ticketVo = await fetchWsTicket(getDeviceId())
  } catch (error) {
    if (error && (error.code === CODE_UNAUTHORIZED || error.code === CODE_KICKED_OUT)) {
      // 登录态已经没了，重连只是反复撞 401；HTTP 拦截器会负责把人送回登录页
      manualClose = true
      socketState.status = 'closed'
      socketState.lastError = error.message
      return
    }
    socketState.lastError = (error && error.message) || '获取连接票据失败'
    scheduleReconnect()
    return
  }

  // 取票据期间可能已经登出或被要求断开
  if (manualClose || !getToken()) {
    socketState.status = 'closed'
    return
  }

  try {
    ws = new WebSocket(wsUrl(ticketVo.endpoint, ticketVo.ticket))
  } catch (error) {
    socketState.lastError = '无法创建连接'
    scheduleReconnect()
    return
  }

  ws.onopen = () => {
    everOpened = true
    attempts = 0
    socketState.status = 'open'
    socketState.lastError = ''
    startHeartbeat()
    dlog('ws open，连接已建立', { reconnected: reconnectCount > 0 })
    emit('open', { reconnected: reconnectCount > 0 })
  }

  ws.onmessage = (event) => {
    let packet
    try {
      packet = JSON.parse(event.data)
    } catch {
      // 非 JSON 帧（代理插入的探活、心跳文本）直接忽略，报错只会污染控制台
      return
    }
    if (!packet || !packet.type) {
      return
    }
    if (packet.type === 'pong') {
      if (pongTimer) {
        clearTimeout(pongTimer)
        pongTimer = null
      }
      return
    }
    if (packet.type === 'notify' && packet.data && packet.data.action === 'connected') {
      // 用心跳帧里的服务端配置覆盖本地默认值，两端节奏始终一致
      const seconds = Number(packet.data.heartbeatSeconds)
      if (Number.isFinite(seconds) && seconds > 0) {
        heartbeatSeconds = seconds
        startHeartbeat()
      }
    }
    if (packet.type === 'kickout') {
      // 服务端推完这帧就会关连接，先置位避免 onclose 触发重连
      manualClose = true
    }
    dlog('ws recv ↓', packet.type, packet)
    emit(packet.type, packet)
  }

  ws.onerror = () => {
    // 浏览器出于安全考虑不给出错误详情，能拿到的只有「出错了」
    socketState.lastError = everOpened ? '连接中断' : '握手失败'
  }

  ws.onclose = (event) => {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    if (pongTimer) {
      clearTimeout(pongTimer)
      pongTimer = null
    }
    ws = null
    dlog('ws close，连接关闭', { code: event.code, reason: event.reason, willReconnect: !manualClose && !!getToken() })
    emit('close', { code: event.code, reason: event.reason, willReconnect: !manualClose && !!getToken() })
    if (manualClose || !getToken()) {
      socketState.status = 'closed'
      return
    }
    scheduleReconnect()
  }
}

function scheduleReconnect() {
  if (reconnectTimer) {
    return
  }
  attempts += 1
  // 指数退避 + 随机抖动：后端重启时成千上万个客户端如果按同一节奏重试，
  // 会在恢复的瞬间打出一个尖峰，抖动把它们摊开
  const base = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** (attempts - 1))
  const delay = base + Math.floor(Math.random() * 500)
  socketState.status = 'reconnecting'
  dlog('ws 准备重连', { attempts, delay })
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    reconnectCount += 1
    openOnce()
  }, delay)
}

/** 建立连接。已经连着或正在连时是空操作，可以安全地在多处调用 */
export function connect() {
  if (manualClose === false && (socketState.status === 'open' || socketState.status === 'connecting')) {
    return
  }
  manualClose = false
  attempts = 0
  reconnectCount = 0
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  openOnce()
}

/**
 * 主动断开（登出、被踢）。
 * 不会触发重连，也不清空已注册的监听器 —— 监听器的生命周期属于组件。
 */
export function disconnect() {
  dlog('ws disconnect，主动断开（登出 / 被踢）')
  manualClose = true
  clearTimers()
  attempts = 0
  if (ws) {
    const current = ws
    ws = null
    // 先摘掉回调再关：否则 onclose 会在 disconnect 的调用栈里同步执行，
    // 里面的 emit('close') 可能触发组件更新，时序上很难推理
    current.onclose = null
    current.onerror = null
    current.onmessage = null
    current.onopen = null
    try {
      current.close(1000, 'client logout')
    } catch {
      // 连接可能已经处于 CLOSED，忽略
    }
  }
  socketState.status = 'closed'
}

/**
 * 页面重新可见时立即检查连接。
 *
 * 标签页在后台时浏览器会大幅降低定时器频率，笔记本合盖休眠更是直接冻结，
 * 醒来后 socket 往往已经死了但要等到下一次心跳超时才发现（最长两个周期）。
 * 这里在可见性恢复时主动探一次，把「切回来半天收不到消息」的空窗压到最短。
 */
function onVisibilityChange() {
  if (document.visibilityState !== 'visible') {
    return
  }
  if (manualClose || !getToken()) {
    return
  }
  if (!isOpen()) {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    attempts = 0
    openOnce()
  } else {
    // 连接看着还在，补一次心跳确认对端也这么认为
    send('ping')
  }
}

if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', onVisibilityChange)
}
