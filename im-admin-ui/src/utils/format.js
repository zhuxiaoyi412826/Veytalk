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
