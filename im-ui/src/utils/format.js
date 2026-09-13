import dayjs from 'dayjs'

/**
 * 展示层格式化。
 *
 * 后端的时间字段是 LocalDateTime，JSON 里形如 2026-09-12T19:55:18，不带时区偏移。
 * dayjs 会按本地时区解析这种字符串，与服务端所在时区一致时显示无误；
 * 这也是这里不做任何时区换算的原因 —— 换算反而会把「服务端墙上时间」挪走。
 */

/** 会话列表右侧的紧凑时间：今天只显示时分，其余逐级放宽 */
export function formatConvTime(value) {
  if (!value) {
    return ''
  }
  const target = dayjs(value)
  if (!target.isValid()) {
    return ''
  }
  const now = dayjs()
  if (target.isSame(now, 'day')) {
    return target.format('HH:mm')
  }
  if (target.isSame(now.subtract(1, 'day'), 'day')) {
    return '昨天'
  }
  if (target.isSame(now, 'year')) {
    return target.format('MM-DD')
  }
  return target.format('YYYY-MM-DD')
}

/** 消息气泡里的完整时间 */
export function formatMsgTime(value) {
  if (!value) {
    return ''
  }
  const target = dayjs(value)
  if (!target.isValid()) {
    return ''
  }
  const now = dayjs()
  if (target.isSame(now, 'day')) {
    return target.format('HH:mm')
  }
  if (target.isSame(now, 'year')) {
    return target.format('MM-DD HH:mm')
  }
  return target.format('YYYY-MM-DD HH:mm')
}

/**
 * 绝对时间：不做「今天 / 昨天」的相对化。
 *
 * 个人中心里的注册时间、上次登录时间要用它 —— 那些字段是存档信息，
 * 显示成「昨天 19:55」过一天再看就变了意思。
 */
export function formatDateTime(value) {
  if (!value) {
    return ''
  }
  const target = dayjs(value)
  return target.isValid() ? target.format('YYYY-MM-DD HH:mm:ss') : ''
}

/**
 * 两条消息之间是否要插入时间分隔线。
 *
 * 间隔阈值取 5 分钟：聊天场景里连续几条消息属于同一段对话，逐条显示时间会让气泡区
 * 全是灰字；超过 5 分钟则通常意味着话题有停顿，值得标一次时间。
 */
export function needTimeDivider(previous, current) {
  if (!previous) {
    return true
  }
  const a = dayjs(previous.sendTime)
  const b = dayjs(current.sendTime)
  if (!a.isValid() || !b.isValid()) {
    return false
  }
  return Math.abs(b.diff(a, 'second')) >= 300
}

/** 字节数转可读文本，与后端 FileConvert 的口径保持一致 */
export function formatFileSize(bytes) {
  const size = Number(bytes)
  if (!Number.isFinite(size) || size <= 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = size
  let unit = 0
  do {
    value /= 1024
    unit++
  } while (value >= 1024 && unit < units.length - 1)
  return `${value.toFixed(1)} ${units[unit]}`
}

/** 语音时长 mm:ss */
export function formatDuration(seconds) {
  const total = Math.max(0, Math.round(Number(seconds) || 0))
  const min = Math.floor(total / 60)
  const sec = total % 60
  return `${min}:${String(sec).padStart(2, '0')}`
}

/** 没有头像时用昵称首字符占位，避免出现一片空白方块 */
export function initialOf(name) {
  const text = String(name || '').trim()
  if (!text) {
    return '?'
  }
  // 中文取首字，英文取首字母大写；用 Array.from 是为了正确处理代理对（emoji 昵称）
  return Array.from(text)[0].toUpperCase()
}

export function genderText(gender) {
  if (gender === 1) {
    return '男'
  }
  if (gender === 2) {
    return '女'
  }
  return '未知'
}

/** 手机号脱敏，用于「发送验证码」等回显场景 */
export function maskPhone(phone) {
  const text = String(phone || '')
  if (text.length < 7) {
    return text
  }
  return text.slice(0, 3) + '****' + text.slice(-4)
}
