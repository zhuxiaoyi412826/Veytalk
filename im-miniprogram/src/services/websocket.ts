import Taro from '@tarojs/taro'
import { getWsBase } from '@/config/runtime'
import type { NotifyData, WsEvent } from '@/types/im'
import { getToken } from '@/utils/session'
import { authApi } from './api'

export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed'
type Listener = (event: WsEvent) => void
type StateListener = (state: ConnectionState) => void

let socket: Taro.SocketTask | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempt = 0
let heartbeatSeconds = 30
let lastPong = 0
let generation = 0
let manualClose = true
let state: ConnectionState = 'idle'
let diagnostic = '尚未发起连接'
let lastError = ''
const listeners = new Set<Listener>()
const stateListeners = new Set<StateListener>()

/** 最近一次连接的诊断信息（界面可见，不依赖 vConsole）；保留上次失败原因，避免被重连重置冲掉 */
export function getDiagnostic(): string {
  return `状态=${state}；${diagnostic}${lastError ? `；上次失败：${lastError}` : ''}`
}

function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms)
    promise.then(
      (value) => { clearTimeout(timer); resolve(value) },
      (error) => { clearTimeout(timer); reject(error) }
    )
  })
}

function describeError(error: unknown): string {
  if (!error) return '未知错误'
  if (typeof error === 'string') return error
  const err = error as { errMsg?: string; message?: string }
  return err.errMsg || err.message || JSON.stringify(error)
}

function setState(next: ConnectionState): void {
  state = next
  stateListeners.forEach((listener) => listener(next))
}

function clearHeartbeat(): void {
  if (heartbeatTimer) clearInterval(heartbeatTimer)
  heartbeatTimer = null
}

function clearReconnect(): void {
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectTimer = null
}

function sendFrame(data: unknown): void {
  if (!socket || state !== 'connected') return
  socket.send({ data: JSON.stringify(data) })
}

/** 上行信令（如通话 invite / cancel）；返回 false 表示连接未就绪，调用方需提示用户 */
export function send(payload: Record<string, unknown>): boolean {
  if (!socket || state !== 'connected') return false
  socket.send({ data: JSON.stringify(payload) })
  return true
}

function startHeartbeat(): void {
  clearHeartbeat()
  lastPong = Date.now()
  heartbeatTimer = setInterval(() => {
    if (Date.now() - lastPong > heartbeatSeconds * 2000) {
      socket?.close({})
      return
    }
    sendFrame({ type: 'ping', timestamp: Date.now() })
  }, heartbeatSeconds * 1000)
}

function dispatch(event: WsEvent): void {
  listeners.forEach((listener) => listener(event))
}

function scheduleReconnect(currentGeneration: number): void {
  if (manualClose || !getToken() || currentGeneration !== generation || reconnectTimer) return
  const delay = Math.min(30000, 1000 * 2 ** reconnectAttempt) + Math.floor(Math.random() * 500)
  reconnectAttempt += 1
  setState('reconnecting')
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    void connect()
  }, delay)
}

export async function connect(): Promise<void> {
  console.error('[WS] connect() 调用, token:', getToken() ? '已存在' : '为空')
  if (!getToken()) {
    diagnostic = '未登录，token为空'
    return
  }
  diagnostic = '正在获取 ws ticket...'
  manualClose = false
  const currentGeneration = ++generation
  clearReconnect()
  clearHeartbeat()
  if (socket) {
    const previous = socket
    socket = null
    if (typeof previous.close === 'function') previous.close({})
  }
  setState(reconnectAttempt ? 'reconnecting' : 'connecting')

  try {
    const ticket = await withTimeout(authApi.wsTicket(), 12000, '获取 ws ticket 超时（12s）')
    console.error('[WS] ticket 获取成功, endpoint:', ticket.endpoint)
    if (currentGeneration !== generation || manualClose) return
    const endpoint = ticket.endpoint.startsWith('/') ? ticket.endpoint : `/${ticket.endpoint}`
    const url = `${getWsBase()}${endpoint}?ticket=${encodeURIComponent(ticket.ticket)}`
    diagnostic = `ticket成功，正在连接 ${getWsBase()}${endpoint}`
    // Taro.connectSocket 在不同平台/版本下可能直接返回 SocketTask，也可能返回 Promise<SocketTask>；
    // 只按 SocketTask 用会报「onOpen is not a function」，连接永远建不起来。两种形态都兼容。
    const raw = Taro.connectSocket({ url }) as unknown
    const task = (raw && typeof (raw as Promise<Taro.SocketTask>).then === 'function'
      ? await (raw as Promise<Taro.SocketTask>)
      : raw) as Taro.SocketTask
    if (!task || typeof task.onOpen !== 'function') {
      throw new Error('connectSocket 未返回可用的 SocketTask')
    }
    if (currentGeneration !== generation || manualClose) {
      if (typeof task.close === 'function') task.close({})
      return
    }
    socket = task
    let opened = false
    const handleOpen = (): void => {
      if (opened) return
      opened = true
      if (currentGeneration !== generation) return
      console.error('[WS] connected')
      diagnostic = 'WebSocket 已连接'
      lastError = ''
      reconnectAttempt = 0
      heartbeatSeconds = 30
      setState('connected')
      startHeartbeat()
    }
    task.onOpen(handleOpen)
    task.onMessage((result) => {
      if (currentGeneration !== generation || typeof result.data !== 'string') return
      try {
        const event = JSON.parse(result.data) as WsEvent
        if (event.type === 'pong') {
          lastPong = Date.now()
          return
        }
        if (event.type === 'notify') {
          const data = event.data as NotifyData | undefined
          if (data?.action === 'connected' && data.heartbeatSeconds) {
            heartbeatSeconds = data.heartbeatSeconds
            startHeartbeat()
          }
        }
        if (event.type === 'kickout') manualClose = true
        lastPong = Date.now()
        dispatch(event)
      } catch {
        // 非 JSON 保活帧不影响主连接。
      }
    })
    task.onClose((result) => {
      console.warn('[WS] closed', result?.code, result?.reason)
      diagnostic = `连接关闭 code=${result?.code} ${result?.reason || ''}`
      lastError = `连接关闭 code=${result?.code} ${result?.reason || ''}`
      if (currentGeneration !== generation) return
      clearHeartbeat()
      socket = null
      setState('closed')
      scheduleReconnect(currentGeneration)
    })
    task.onError((error) => {
      console.error('[WS] socket error', error)
      diagnostic = `socket错误: ${describeError(error)}`
      lastError = `socket错误: ${describeError(error)}`
      if (currentGeneration !== generation) return
      clearHeartbeat()
      setState('closed')
    })
    // 若 connectSocket 是在「已连接」时才 resolve，open 事件已经过去，这里补一次
    if ((task as unknown as { readyState?: number }).readyState === 1) handleOpen()
  } catch (error) {
    console.error('[WS] connect failed', error)
    diagnostic = `连接异常: ${describeError(error)}`
    lastError = `取票据/建连接失败: ${describeError(error)}`
    if (currentGeneration === generation) scheduleReconnect(currentGeneration)
  }
}

export function disconnect(): void {
  manualClose = true
  generation += 1
  reconnectAttempt = 0
  clearHeartbeat()
  clearReconnect()
  const current = socket
  socket = null
  if (current && typeof current.close === 'function') current.close({})
  setState('idle')
}

export function reconnect(): void {
  disconnect()
  void connect()
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function subscribeState(listener: StateListener): () => void {
  stateListeners.add(listener)
  listener(state)
  return () => stateListeners.delete(listener)
}
