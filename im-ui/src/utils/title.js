/**
 * 浏览器标签页标题。
 *
 * 标题由「未读数 + 当前栏目」两部分拼成，而这两部分的变更时机完全不同：
 * 栏目跟着路由走，未读数跟着 WebSocket 推送与会话接口走。
 * 任何一侧单独改 document.title 都会把另一侧的信息抹掉，
 * 所以把两个输入集中到这里，各自只负责更新自己那一份。
 *
 * 本模块不依赖任何 store 与 router，两边都可以安全地单向引用它。
 *
 * render() 现在管的是「未读数在标签页上看得见的三处」：标题前缀、图标角标、
 * 以及桌面壳的任务栏闪烁（未点开的通知在 Windows 上只能靠闪烁提示）。
 * 三处都只读同一个 unread，不需要各自再算一遍。
 */

import { setFaviconBadge } from '@/utils/favicon'

const APP_NAME = 'IM 即时通讯'

let section = ''
let unread = 0

function render() {
  // 99+ 而不是真实数字：未读到三位数时精确值已经没有意义，只会把标签页标题挤没
  const head = unread > 0 ? `(${unread > 99 ? '99+' : unread}) ` : ''
  document.title = section ? `${head}${section} - ${APP_NAME}` : `${head}${APP_NAME}`
  setFaviconBadge(unread)
  // 桌面壳：窗口没聚焦时让任务栏图标闪烁，聚焦或清空未读就停。
  // 浏览器里没有这个能力（也没有对应 API），桥不存在时整句跳过
  const badge = typeof window !== 'undefined' && window.__IM_NATIVE__ ? window.__IM_NATIVE__.badge : null
  if (badge) {
    try {
      badge(unread)
    } catch {
      /* IPC 不可用时只是不闪，不影响标题与角标 */
    }
  }
}

/** 由 router.afterEach 调用 */
export function setSectionTitle(title) {
  section = title || ''
  render()
}

/** 由 conversation store 调用 */
export function setUnreadCount(count) {
  const next = Number(count) || 0
  if (next === unread) {
    return
  }
  unread = next
  render()
}
