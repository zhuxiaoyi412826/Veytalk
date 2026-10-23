import { useSettingsStore } from '@/stores/settings'
import { asId } from '@/utils/id'

/**
 * 消息提醒的统一出口：系统桌面通知 + 提示音。
 *
 * 为什么单独成一层：提醒的判定条件散在很多处（是不是正在看这个会话、页面在不在前台、
 * 这个会话免不免打扰、这台设备有没有通知权限、是浏览器还是桌面壳），
 * 写在 dispatch 里会让那个文件变成一堆 if 的坟场，也没法在「离线补拉」「好友申请」
 * 这些别的入口复用同一套取舍。
 *
 * 三种场景的口径（对齐桌面 IM 的常识，也解释了他说的「切后台就收不到」）：
 * - 在前台且正打开着这个会话：什么都不做。用户已经看见了，响一声都是打扰。
 * - 在前台但没看这个会话：只响提示音。屏幕正亮着，再弹系统通知是重复提醒。
 * - 在后台/最小化：提示音 + 一条系统通知。这一档才是「收不到消息」的真正缺口。
 * - 免打扰会话不出声也不弹通知，**但被 @ 时例外**：后端 MessageExtra.hasMention() 就是这个口径
 *   （「这个群我设了免打扰」不该吃掉「有人点名找我」），前端只静默普通消息。
 *
 * 通知在两种宿主里走不同通道：Electron 桌面壳走主进程的原生通知（窗口隐藏时也能弹，
 * 点击能把窗口唤回来），纯浏览器走 Web Notification API。后者有个必须知道的限制：
 * 非安全上下文（http://局域网IP 直接访问）下浏览器根本不给权限，这时自动降级为
 * 「提示音 + 标题/图标角标」，功能不塌，只是少一条弹窗。
 */

/** 同一会话在这个窗口期内只提醒一次：连发八条消息弹八条通知，用户会直接把通知权限关掉 */
const THROTTLE_MS = 4000

/** 通知自动收起的时间，和 ElNotification 的量级一致 */
const NOTIFY_TTL_MS = 8000

/**
 * 按会话 id 记着「当前这条提醒」的节流截止时间与已合并条数。
 * @type {Map<string, {until: number, count: number, notification: object|null, timer: number|null}>}
 */
const buckets = new Map()

/** 由 dispatch 注入：点通知后要跳到哪个会话（notify 这层不认识 router，也不该认识） */
let openHandler = null

/** 延迟读 settings store：本模块可能在 pinia 装好之前被 import，读值必须等到真正提醒那一刻 */
function settings() {
  try {
    return useSettingsStore()
  } catch {
    return {}
  }
}

function desktopShell() {
  return (typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.notify) || null
}

/* ------------------------------ 权限 ------------------------------ */

/**
 * 当前能提醒到什么程度。
 *
 * 'desktop' 是桌面壳的主进程原生通知，不受浏览器权限策略约束；
 * 'granted'/'denied'/'prompt' 是浏览器 Notification 的三态；
 * 'unsupported' 包括手机浏览器（基本没有前台服务可用）与非安全上下文。
 */
export function notifyCapability() {
  if (desktopShell()) {
    return 'desktop'
  }
  if (typeof window === 'undefined' || typeof Notification === 'undefined') {
    return 'unsupported'
  }
  try {
    return Notification.permission
  } catch {
    return 'unsupported'
  }
}

/** 申请浏览器通知权限。必须由用户手势触发，否则 Chrome 会静默拒绝 */
export async function requestNotifyPermission() {
  if (typeof Notification === 'undefined') {
    return 'unsupported'
  }
  if (Notification.permission === 'granted' || Notification.permission === 'denied') {
    return Notification.permission
  }
  try {
    // 老浏览器这一版返回回调而不是 Promise
    const result = await Notification.requestPermission()
    return result || Notification.permission
  } catch {
    return 'unsupported'
  }
}

/** 点通知后的跳转回调，参数是 conversationId */
export function setOpenConversationHandler(handler) {
  openHandler = typeof handler === 'function' ? handler : null
}

/* ------------------------------ 提示音 ------------------------------ */

let audioCtx = null

function ensureAudioContext() {
  const Ctor = typeof window !== 'undefined' && (window.AudioContext || window.webkitAudioContext)
  if (!Ctor) {
    return null
  }
  if (!audioCtx) {
    try {
      audioCtx = new Ctor()
    } catch {
      return null
    }
  }
  // 自动播放策略下上下文会以 suspended 创建，不 resume 就等于没声音；
  // resume 是异步的且可能直接被拒（用户从没点过页面），这里不 await，失败静默
  if (audioCtx.state === 'suspended') {
    audioCtx.resume().catch(() => {})
  }
  return audioCtx
}

/**
 * 合成一声短提示音，不引任何音频文件。
 *
 * 为什么不放一段 mp3：多一个静态资源与一次网络请求，而「叮」这种两声短音用两个
 * 正弦波包络就够，音量还能跟着系统设置走（浏览器里那条路是标签页音量）。
 * @param {'message'|'system'} kind message 是两声上行的提示，system 是一声较平的提示
 */
export function playChime(kind = 'message') {
  const ctx = ensureAudioContext()
  if (!ctx || ctx.state !== 'running') {
    return
  }
  const notes = kind === 'system' ? [[660, 0.16]] : [[784, 0.11], [1046, 0.13]]
  let offset = 0
  notes.forEach(([freq, dur]) => {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = freq
    // 起音稍慢、结尾收干净：纯方波包络会听出一声「咔」
    gain.gain.setValueAtTime(0.0001, ctx.currentTime + offset)
    gain.gain.exponentialRampToValueAtTime(0.09, ctx.currentTime + offset + 0.02)
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + offset + dur)
    osc.connect(gain).connect(ctx.destination)
    osc.start(ctx.currentTime + offset)
    osc.stop(ctx.currentTime + offset + dur + 0.02)
    offset += dur + 0.03
  })
}

/**
 * 试听：先把上下文真的唤回 running 再放声，返回到底响没响。
 *
 * 与直接调 playChime 的区别只在「等不等 resume」：playChime 是给消息路径用的，
 * 那里不能为了一个提示音 await（音不出来还有通知和角标兜着）；而试听这个场景里，
 * 用户点按钮就是要听这一声，resume 是异步的，不等就大概率第一下没声音。
 * @returns {Promise<boolean>} false 表示仍被自动播放策略拦着（这一页还没拿到有效手势）
 */
export async function previewChime(kind = 'message') {
  const ctx = ensureAudioContext()
  if (!ctx) {
    return false
  }
  if (ctx.state !== 'running') {
    try {
      await ctx.resume()
    } catch {
      // 被策略拒绝时 state 还是 suspended，下面统一按「没响」返回
    }
  }
  if (ctx.state !== 'running') {
    return false
  }
  playChime(kind)
  return true
}

/* ------------------------------ 通知本体 ------------------------------ */

function showDesktopNotification({ title, body, tag, silent }) {
  const api = desktopShell()
  if (!api) {
    return false
  }
  try {
    // 桌面壳存在就交给主进程：它是真正的系统通知，窗口隐藏也能弹、点击能唤回窗口，
    // 不再叠一份 Web Notification。系统不支持原生通知时由主进程兜底（闪任务栏）。
    api.notify({ title, body, tag: tag || '', silent: !!silent })
    return true
  } catch {
    return false
  }
}

function showWebNotification({ title, body, tag, onOpen }) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') {
    return null
  }
  let instance = null
  try {
    instance = new Notification(title, {
      body: body || '',
      tag: tag || undefined,
      // 图标留空：浏览器会用它自己的默认页签图标，比我们塞一张 png 更贴合系统观感
      silent: false
    })
  } catch {
    // 部分浏览器（老的 Android WebView）new Notification 直接抛错，降级为只有提示音
    return null
  }
  const timer = setTimeout(() => {
    try {
      instance.close()
    } catch {
      /* 已经被系统关掉了 */
    }
  }, NOTIFY_TTL_MS)
  instance.onclick = () => {
    clearTimeout(timer)
    try {
      window.focus()
    } catch {
      /* focus 在部分浏览器会被拒，不影响跳转 */
    }
    if (onOpen) {
      onOpen()
    }
    try {
      instance.close()
    } catch {
      /* 忽略 */
    }
  }
  return instance
}

/** 关掉某会话当前挂着的那条通知（回到前台、或会话被点开时都要做） */
function closeBucketNotification(key) {
  const bucket = buckets.get(key)
  if (!bucket) {
    return
  }
  if (bucket.timer) {
    clearTimeout(bucket.timer)
    bucket.timer = null
  }
  if (bucket.notification) {
    try {
      bucket.notification.close()
    } catch {
      /* 已被系统回收 */
    }
    bucket.notification = null
  }
}

/**
 * 一条新消息的提醒判定，全部取舍都在这一个函数里。
 *
 * @param {object} options
 * @param {string|number} options.conversationId 哪来的
 * @param {string} [options.name] 会话名，作为通知标题
 * @param {string} [options.summary] 摘要正文（附件类要转成「[图片]」那种占位，别把文件 ID 抄进通知）
 * @param {boolean} [options.muted] 该会话是否免打扰
 * @param {boolean} [options.important] 群里的 @我 / @全体，无视前台静默
 * @param {boolean} [options.viewing] 用户此刻正打开着这个会话
 */
export function notifyIncoming(options = {}) {
  const { conversationId, name, summary, muted, important, viewing } = options
  const conf = settings()
  if (conf.notifyEnabled === false || (muted && !important)) {
    return
  }
  const key = asId(conversationId)
  const now = Date.now()
  const bucket = buckets.get(key) || { until: 0, count: 0, notification: null, timer: null }
  const foreground = typeof document !== 'undefined' && document.visibilityState === 'visible'

  if (foreground && viewing && !important) {
    return
  }
  if (now < bucket.until) {
    // 节流窗口内：不再响第二次，但把条数攒起来，下一条到期提醒时一并写进文案
    bucket.count += 1
    buckets.set(key, bucket)
    return
  }

  bucket.until = now + THROTTLE_MS
  bucket.count += 1
  const count = bucket.count
  buckets.set(key, bucket)
  if (bucket.timer) {
    clearTimeout(bucket.timer)
    bucket.timer = null
  }
  // 窗口结束后把计数清零，下一批消息重新从「1 条」开始，也不会留着一条过期通知
  bucket.timer = setTimeout(() => {
    bucket.count = 0
    closeBucketNotification(key)
    buckets.delete(key)
  }, THROTTLE_MS)

  // 前台只做「响一声」，后台才追加一条系统通知
  if (!foreground) {
    const title = count > 1 ? `${name || '新消息'} · ${count} 条` : name || '新消息'
    const body = summary || ''
    const payload = { title, body, tag: key, onOpen: () => openHandler && openHandler(key) }
    if (!showDesktopNotification(payload) && conf.notifyDesktop !== false) {
      bucket.notification = showWebNotification(payload)
    }
    if (conf.notifySound !== false) {
      playChime(important ? 'system' : 'message')
    }
    return
  }
  if (conf.notifySound !== false) {
    playChime(important ? 'system' : 'message')
  }
}

/**
 * 离线/重连补拉到一批消息后的汇总提醒。
 *
 * 这批消息上屏是静默的（走 appendMessage，不经 message 帧那条路径），
 * 用户切回来只看到气泡多了几条、什么声音都没有 —— 这正是「切后台就收不到」的后半段。
 * 这里把它们汇成一条通知，而不是逐条弹：断线两分钟回来弹十几条会把人吓一跳。
 *
 * @param {Array} list 补拉到的消息（MessageVO/DTO 形状）
 * @param {object} ctx 取值上下文：{ info(message) => { name, muted, important } }，
 *                     important 同样能打破免打扰（判定口径与 notifyIncoming 一致）
 */
export function notifyOfflineBatch(list, ctx = {}) {
  if (!list || list.length === 0) {
    return
  }
  const conf = settings()
  if (conf.notifyEnabled === false) {
    return
  }
  const names = []
  let counted = 0
  let mentioned = 0
  const seen = new Set()
  list.forEach((item) => {
    const key = asId(item.conversationId)
    const info = (ctx.info && ctx.info(item)) || {}
    if (info.muted && !info.important) {
      return
    }
    if (info.important) {
      mentioned += 1
    }
    counted += 1
    if (!seen.has(key)) {
      seen.add(key)
      names.push(info.name || '新消息')
    }
  })
  if (counted === 0) {
    // 全都被免打扰覆盖了：未读角标已经更新过，这里不该出任何声
    return
  }
  const title = `离线期间收到 ${counted} 条新消息${mentioned > 0 ? `（${mentioned} 条@我）` : ''}`
  const body = names.slice(0, 3).join('、') + (names.length > 3 ? ` 等 ${names.length} 个会话` : '')
  if (conf.notifyDesktop !== false && typeof document !== 'undefined' && document.visibilityState !== 'visible') {
    const payload = { title, body, tag: 'offline-batch', onOpen: null }
    if (!showDesktopNotification(payload)) {
      showWebNotification(payload)
    }
  }
  if (conf.notifySound !== false) {
    playChime('message')
  }
}

/**
 * 非聊天类事件（好友申请、入群审批、举报回执）的后台补提醒。
 *
 * 调用点在 dispatch：那边照旧弹 ElNotification 给前台的用户看，这里只负责
 * 「页面在后台时 ElNotification 是看不见的」这半边，同一条事件不重复弹两次。
 */
export function notifySystem(title, body, options = {}) {
  const conf = settings()
  if (conf.notifyEnabled === false || conf.notifyDesktop === false) {
    return
  }
  if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
    return
  }
  const payload = {
    title: title || '提醒',
    body: body || '',
    tag: options.tag || `system-${title || ''}`,
    onOpen: options.onOpen || null
  }
  if (!showDesktopNotification(payload)) {
    showWebNotification(payload)
  }
  if (conf.notifySound !== false) {
    playChime('system')
  }
}

/** 会话被点开/整页回到前台时，把该会话挂着的通知收掉 */
export function clearConversationNotification(conversationId) {
  if (conversationId === undefined || conversationId === null) {
    buckets.forEach((_, key) => closeBucketNotification(key))
    return
  }
  const key = asId(conversationId)
  closeBucketNotification(key)
  buckets.delete(key)
}

/* ------------------------------ 生命周期 ------------------------------ */

if (typeof document !== 'undefined') {
  // 回到前台的瞬间收掉所有挂着的后台通知：用户已经在看屏幕了，那些积压没有存在价值，
  // 留着还会让他以为「通知一直在响」
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      buckets.forEach((_, key) => closeBucketNotification(key))
    }
  })
}

if (typeof window !== 'undefined') {
  const api = desktopShell()
  if (api && api.onClicked) {
    // 桌面壳的系统通知是主进程弹的，点击事件只能从主进程回来
    api.onClicked((tag) => {
      if (tag && tag !== 'offline-batch') {
        clearConversationNotification(tag)
        if (openHandler) {
          openHandler(tag)
        }
      }
    })
  }
}
