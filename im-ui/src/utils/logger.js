/**
 * 前端调试日志开关。
 *
 * 独立成一个不依赖 store 的小模块：ws/socket、ws/dispatch 这些底层模块在 store
 * 初始化之前就可能被 import，若它们反过来依赖 settings store 会形成循环引用。
 * 这里只在模块级维护一个布尔量，由 settings store 在应用/变更时调用 setDebugEnabled 同步，
 * 底层模块只管 import dlog 即可，读到的永远是最新开关状态。
 */

let enabled = false

/** 由 settings store 调用，把持久化的开关同步进来 */
export function setDebugEnabled(value) {
  enabled = !!value
}

export function isDebugEnabled() {
  return enabled
}

/**
 * 受开关控制的调试日志。关闭时是彻底的空操作，
 * 不会像 console.log 那样在控制台留下噪音，也不产生字符串拼接开销。
 */
export function dlog(...args) {
  if (enabled) {
    console.debug('[im]', ...args)
  }
}
