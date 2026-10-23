import { onBeforeUnmount, ref } from 'vue'
import { isElectron } from './env'

/**
 * 窄屏（手机浏览器）判定。
 *
 * 刻意排除 Electron：桌面端窗口被用户拉窄时不该跟着变成「手机布局」——
 * 需要按端区分样式的场景（如文件预览铺满整屏）只针对移动浏览器。
 */
export function isNarrowViewport() {
  if (typeof window === 'undefined') {
    return false
  }
  if (isElectron()) {
    return false
  }
  const width = window.innerWidth
  if (width <= 768) {
    return true
  }
  // 平板 / 大屏手机：宽度不算窄但有触摸能力，按移动端处理更符合直觉
  return width <= 1024 && ('ontouchstart' in window || navigator.maxTouchPoints > 0)
}

/**
 * 响应式的窄屏状态：挂载时取一次，resize 时同步，卸载时摘监听。
 *
 * 用 Composable 而不是让每个组件各写一份，是为了让「什么算手机」在全项目只有一个定义。
 */
export function useNarrowViewport() {
  const narrow = ref(isNarrowViewport())
  if (typeof window === 'undefined') {
    return narrow
  }
  const sync = () => {
    narrow.value = isNarrowViewport()
  }
  window.addEventListener('resize', sync)
  onBeforeUnmount(() => window.removeEventListener('resize', sync))
  return narrow
}

let pcPointerCache = null

/**
 * 是否是「精细指针」设备（鼠标这类可悬停、能精确点中 16px 目标的）。
 *
 * 判据用 `(pointer: fine)` 而不是屏宽或 UA：手机浏览器与 App 的 webview 都是 coarse，
 * 电脑浏览器与桌面壳都是 fine，窗口拉大拉小不会把功能判丢（屏宽那条会）。
 * 与 `isNarrowViewport()` 是两个轴：一个说「手指点的」，一个说「屏幕窄」。
 *
 * 结果缓存在模块级：一台设备的指针类型不会中途变，而消息气泡是上百个实例，
 * 每个都去 matchMedia 一次纯属浪费。
 */
export function isPcPointer() {
  if (pcPointerCache !== null) {
    return pcPointerCache
  }
  if (typeof window === 'undefined' || !window.matchMedia) {
    // SSR / 测试环境没有 window，按桌面处理（宁多给一个入口，不要莫名少了功能）
    pcPointerCache = true
    return pcPointerCache
  }
  pcPointerCache = window.matchMedia('(pointer: fine)').matches
  return pcPointerCache
}
