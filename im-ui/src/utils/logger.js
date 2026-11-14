/**
 * 前端调试日志开关。
 *
 * 独立成一个不依赖 store 的小模块：ws/socket、ws/dispatch 这些底层模块在 store
 * 初始化之前就可能被 import，若它们反过来依赖 settings store 会形成循环引用。
 * 这里只在模块级维护一个布尔量，由 settings store 在应用/变更时调用 setDebugEnabled 同步，
 * 底层模块只管 import dlog 即可，读到的永远是最新开关状态。
 *
 * 新增能力：
 * - onLog(callback) 订阅机制，供屏幕浮层组件实时接收日志条目
 * - 桌面端通过 imBridge.log.write() 落盘到 app.log
 */

let enabled = false

/** 订阅者列表，每个回调收到 { level, message, timestamp } */
const listeners = []

/** 由 settings store 调用，把持久化的开关同步进来 */
export function setDebugEnabled(value) {
  const prev = enabled
  enabled = !!value
  // 从关到开时，清除旧的 app.log 确保本次会话干净
  if (!prev && enabled && typeof window !== 'undefined' && window.imBridge?.log) {
    window.imBridge.log.clear()
  }
}

export function isDebugEnabled() {
  return enabled
}

/**
 * 注册日志回调，返回取消订阅函数。组件 onMounted 时订阅，onUnmounted 时取消。
 */
export function onLog(callback) {
  listeners.push(callback)
  return () => {
    const idx = listeners.indexOf(callback)
    if (idx !== -1) listeners.splice(idx, 1)
  }
}

/**
 * 受开关控制的调试日志。关闭时是彻底的空操作，
 * 不会像 console.log 那样在控制台留下噪音，也不产生字符串拼接开销。
 */
export function dlog(...args) {
  if (!enabled) return
  const message = args.map((a) => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ')
  const timestamp = Date.now()
  // 1. 浏览器控制台
  console.debug('[im]', ...args)
  // 2. 屏幕浮层订阅者
  const entry = { level: 'debug', message, timestamp }
  for (const fn of listeners) {
    try { fn(entry) } catch { /* 防止某个订阅者抛异常影响其它 */ }
  }
  // 3. 桌面端文件落盘
  if (typeof window !== 'undefined' && window.imBridge?.log) {
    window.imBridge.log.write(entry)
  }
}
