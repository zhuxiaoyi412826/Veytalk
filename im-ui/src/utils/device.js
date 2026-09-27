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
