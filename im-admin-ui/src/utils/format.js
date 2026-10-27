import dayjs from 'dayjs'

/**
 * 后台的展示层格式化。
 *
 * 后端时间字段是 LocalDateTime，JSON 里形如 2026-09-12T19:55:18（不带时区偏移），
 * dayjs 按本地时区解析这种串；和 im-ui 的 utils/format 同一个前提，也不做任何时区换算——
 * 换算只会把服务端墙上的时间挪走。这份不合并成一个包是两个工程各自独立 npm 安装，
 * 共享一个 utils 就得引入 workspace 或 monorepo 工具链，为一个 60 行的文件不值当。
 */

/** 空值的显示占位：后台宁可看到一个「—」也不要一个可能被读成事实的 0 */
export const EMPTY_TEXT = '—'

export function displayValue(value) {
  if (value === null || value === undefined || value === '') {
    return EMPTY_TEXT
  }
  return value
}

/** 完整时间，工单详情与注册时间用 */
export function formatDateTime(value) {
  if (!value) {
    return EMPTY_TEXT
  }
  const target = dayjs(value)
  return target.isValid() ? target.format('YYYY-MM-DD HH:mm:ss') : String(value)
}

/** 列表用的紧凑时间：今年省略年份，跨年补回，免得去年的工单看着像刚发生的 */
export function formatListTime(value) {
  if (!value) {
    return EMPTY_TEXT
  }
  const target = dayjs(value)
  if (!target.isValid()) {
    return String(value)
  }
  const pattern = target.isSame(dayjs(), 'year') ? 'MM-DD HH:mm' : 'YYYY-MM-DD HH:mm'
  return target.format(pattern)
}

/** 「3 分钟前」这类相对说法，用于看板的「数据截至」 */
export function formatFromNow(value) {
  if (!value) {
    return EMPTY_TEXT
  }
  const target = dayjs(value)
  if (!target.isValid()) {
    return String(value)
  }
  const seconds = dayjs().diff(target, 'second')
  if (seconds < 60) {
    return `${Math.max(seconds, 0)} 秒前`
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)} 分钟前`
  }
  return `${Math.floor(seconds / 3600)} 小时前`
}

/** 举报趋势的日期刻度：只到天 */
export function formatDay(value) {
  if (!value) {
    return ''
  }
  const target = dayjs(value)
  return target.isValid() ? target.format('MM-DD') : String(value)
}

/** 千分位整数，看板上的人数与金额都读得清一些 */
export function formatNumber(value) {
  if (value === null || value === undefined || value === '') {
    return EMPTY_TEXT
  }
  const num = Number(value)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : String(value)
}

/**
 * 账号状态 → Element Plus 标签类型。
 *
 * status 为 null 表示<b>账号已注销</b>（im_user 那行没了但工单还在），
 * 这既不是正常也不是禁用，得单独一种说法，否则会显示成「正常」而误导处置。
 */
export function accountStatusTag(status) {
  if (status === 1) {
    return { type: 'success', text: '正常' }
  }
  if (status === 0) {
    return { type: 'danger', text: '已禁用' }
  }
  return { type: 'info', text: '已注销' }
}

/**
 * 工单状态 → 标签类型。码表在服务端（0 待处理 1 已受理 2 已驳回 3 已处置），
 * 中文名直接取接口里的 statusName，这里只补颜色——颜色是前端的事，文案不是。
 */
export function reportStatusTag(status) {
  switch (status) {
    case 0:
      return 'warning'
    case 1:
      return 'primary'
    case 2:
      return 'info'
    case 3:
      return 'success'
    default:
      return 'info'
  }
}

/**
 * 公告状态 → 标签类型。文案取接口里的 statusName（服务端拼好下发的），
 * 这里只补颜色，与 reportStatusTag 同一条规矩。
 *
 * 已撤回给 warning 而不是 info：它跟「草稿」一样不该对用户可见，但两者要能一眼分开——
 * 前者是「说过又收回」，后者是「还没说」，混在一个颜色里就会让人以为撤回的行也是待发状态。
 */
export function announcementStatusTag(status) {
  switch (status) {
    case 0:
      return 'info'
    case 1:
      return 'success'
    case 2:
      return 'warning'
    default:
      return 'info'
  }
}

/** 字节数 → 人话大小（视频文件与日志文件都读这个），取不到显示「—」而不是 0 B */
export function formatBytes(value) {
  if (value === null || value === undefined || value === '') {
    return EMPTY_TEXT
  }
  const num = Number(value)
  if (!Number.isFinite(num)) {
    return String(value)
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = num
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  // B 与 KB 级保留整数就够，MB 往上才需要小数
  return `${unit <= 1 ? Math.round(size) : size.toFixed(1)} ${units[unit]}`
}

/** 秒 → 「3:05」/「1:02:03」，视频时长用；空值同样走「—」口径 */
export function formatDuration(seconds) {
  if (seconds === null || seconds === undefined || seconds === '') {
    return EMPTY_TEXT
  }
  const total = Math.max(Math.floor(Number(seconds) || 0), 0)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  return h > 0 ? `${h}:${mm}:${String(s).padStart(2, '0')}` : `${mm}:${String(s).padStart(2, '0')}`
}

/** 秒 → 「2 天 3 小时」「15 分 40 秒」这类说法，监控页的运行时长用 */
export function formatUptime(seconds) {
  if (seconds === null || seconds === undefined || seconds === '') {
    return EMPTY_TEXT
  }
  const total = Math.max(Math.floor(Number(seconds) || 0), 0)
  if (total < 60) {
    return `${total} 秒`
  }
  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  if (days > 0) {
    return `${days} 天 ${hours} 小时`
  }
  if (hours > 0) {
    return `${hours} 小时 ${minutes} 分`
  }
  return `${minutes} 分 ${total % 60} 秒`
}
