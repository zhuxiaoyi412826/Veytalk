/**
 * 标签页图标的未读角标。
 *
 * 为什么需要它：标题里的 `(3)` 会被标签页挤窄后裁掉，而用户在十几个标签页里找回 IM
 * 靠的是图标不是文字。切后台收不到提醒这件事，能「一眼看见哪条标签页有新消息」就解决了一半。
 *
 * 本项目原本没有 favicon（index.html 里没有那一行 link），浏览器只会画一枚默认图标，
 * 所以这里索性用 canvas 现画一枚：它既是应用图标，也是角标的载体。
 * 不引一张现成的 png 是因为角标要跟着未读数变，而主题色又能被用户在设置里改；
 * 现画的代价是几十行代码，换来的是「不必每改一次样式就重画一张图并维护一条路径」。
 */

const SIZE = 32

/** 上一次画的未读数，用来避免每次角标变更都重画一次并替换 link */
let lastCount = -1
/** 最近一次要求显示的未读数：主题色变了要按它重画，不能退回 0 */
let currentCount = 0
/** 复用的 link 节点，第一次之后就不再往 head 里插了 */
let linkEl = null

function readThemeColor() {
  try {
    const value = getComputedStyle(document.documentElement).getPropertyValue('--im-primary').trim()
    return /^#[0-9a-fA-F]{3,8}$/.test(value) ? value : '#409eff'
  } catch {
    return '#409eff'
  }
}

/** 圆角矩形：canvas 有 roundRect 的浏览器用它，老浏览器手描四角 */
function roundedRect(ctx, x, y, w, h, r) {
  if (typeof ctx.roundRect === 'function') {
    ctx.beginPath()
    ctx.roundRect(x, y, w, h, r)
    return
  }
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + w - r, y)
  ctx.arcTo(x + w, y, x + w, y + r, r)
  ctx.lineTo(x + w, y + h - r)
  ctx.arcTo(x + w, y + h, x + w - r, y + h, r)
  ctx.lineTo(x + r, y + h)
  ctx.arcTo(x, y + h, x, y + h - r, r)
  ctx.lineTo(x, y + r)
  ctx.arcTo(x, y, x + r, y, r)
  ctx.closePath()
}

function paint(count) {
  const canvas = document.createElement('canvas')
  canvas.width = SIZE
  canvas.height = SIZE
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return ''
  }

  ctx.fillStyle = readThemeColor()
  roundedRect(ctx, 0, 0, SIZE, SIZE, 7)
  ctx.fill()

  // 聊天气泡主体 + 左下的小尾巴
  ctx.fillStyle = '#ffffff'
  roundedRect(ctx, 6, 8, 20, 14, 5)
  ctx.fill()
  ctx.beginPath()
  ctx.moveTo(11, 21)
  ctx.lineTo(11, 26)
  ctx.lineTo(17, 21)
  ctx.closePath()
  ctx.fill()

  if (count > 0) {
    const text = count > 99 ? '99' : String(count)
    ctx.fillStyle = '#f56c6c'
    ctx.beginPath()
    ctx.arc(25, 25, 8, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillStyle = '#ffffff'
    // 16px 的显示尺寸下两位数字其实已经看不清了，但比只画一个红点信息量大：
    // 悬停放大预览、以及 2x 屏上它是可读的
    ctx.font = `${text.length > 1 ? '7px' : '9px'} sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(text, 25, 26)
  }

  try {
    return canvas.toDataURL('image/png')
  } catch {
    return ''
  }
}

/**
 * 更新角标。
 *
 * @param {number} count 全部会话的未读总数，<=0 时画干净的应用图标
 */
export function setFaviconBadge(count) {
  if (typeof document === 'undefined' || typeof window === 'undefined') {
    return
  }
  const next = Math.max(0, Number(count) || 0)
  currentCount = next
  if (next === lastCount) {
    return
  }
  lastCount = next
  try {
    const href = paint(next)
    if (!href) {
      return
    }
    if (!linkEl) {
      linkEl = document.querySelector("link[rel='icon']") || document.createElement('link')
      linkEl.rel = 'icon'
      linkEl.type = 'image/png'
      document.head.appendChild(linkEl)
    }
    linkEl.href = href
  } catch {
    // 画图标失败不影响任何功能，安静放弃即可（隐私模式下 canvas 可能被污染或限流）
  }
}

/**
 * 按当前未读数重画一次：底色取自主题色，用户在设置里换了主题色就得调这里，
 * 否则角标会一直是旧颜色（或者被误当成「清成 0」而把未读标记抹掉）。
 */
export function refreshFavicon() {
  lastCount = -1
  setFaviconBadge(currentCount)
}
