import dayjs from 'dayjs'
import { formatUptime } from './format'

/**
 * 封禁时长相关的显示与选项。
 *
 * 单独成文件是因为两个页面要共用同一份档位（用户管理的「禁用」与工单抽屉里的「封禁账号」）：
 * 两边各写一份下拉，早晚会长出一个「用户页能封 6 小时、工单页不能」这种没人解释得清的差别。
 *
 * 档位是给管理员的便利，不是服务端的约束——后端只认「正数分钟、上限 365 天」这一条。
 * 自定义档就是为此存在的：预设永远覆盖不全，而逼人先去改代码才能选出一个时长，
 * 只会让管理员绕到数据库去 UPDATE。
 */

/** 「永久封禁」在选项里的值：后端把不传 minutes 当永久，所以这里必须是 null 而不是 0 */
export const PERMANENT = null

/** 「自定义」档的标记值；选中后再显示一个分钟数输入框 */
export const CUSTOM = 'custom'

/** 自定义分钟数的上限，与后端 BAN_MINUTES_MAX 同口径（365 天） */
export const CUSTOM_MAX_MINUTES = 365 * 24 * 60

/**
 * 封禁时长档位。value 是分钟数，null 表示永久。
 *
 * 最短一档给到 10 分钟而不是 1 分钟：自动解封任务每 30 秒扫一轮，1 分钟一档的实际
 * 到期时间会在「55 秒到 90 秒」之间浮动，而这么短的封禁本来就是误操作温床。
 */
export const BAN_DURATIONS = [
  { value: PERMANENT, label: '永久（不自动解封）' },
  { value: 10, label: '10 分钟' },
  { value: 30, label: '30 分钟' },
  { value: 60, label: '1 小时' },
  { value: 360, label: '6 小时' },
  { value: 1440, label: '1 天' },
  { value: 4320, label: '3 天' },
  { value: 10080, label: '7 天' },
  { value: 43200, label: '30 天' },
  { value: CUSTOM, label: '自定义分钟数' }
]

/**
 * 把分钟数说成一句人话，用于回执与确认文案。
 *
 * 只取一个最大单位（「2 天 6 小时」这种拼接在按钮文案里只会变长，不多给信息）；
 * 与服务端 ReportUserTargetSpiImpl 里那个同名函数是同一套口径，两处都是显示层的粗算，
 * 真正的时间事实只有一个：库里那一列 disable_until。
 */
export function durationText(minutes) {
  if (!minutes || minutes <= 0) {
    return '永久'
  }
  if (minutes % (60 * 24) === 0) {
    return `${minutes / (60 * 24)} 天`
  }
  if (minutes % 60 === 0) {
    return `${minutes / 60} 小时`
  }
  return `${minutes} 分钟`
}

/**
 * 剩余时长的文案，比如「2 天 3 小时」。
 *
 * 传空值代表<b>永久封禁</b>，调用方需要先按 {@code status} 判过（正常账号的 disableUntil 也
 * 是空，直接在这里回一句「永久」就会把没被封过的人标成永久封禁）。
 *
 * 时间已经过了但还没被后端扫到（自动解封每 30 秒一轮）时回「解封处理中」，
 * 不回显那个过去的时刻：给管理员看一个负数剩余，只会让人怀疑是不是自己算错了。
 */
export function banRemainingText(until) {
  if (!until) {
    return ''
  }
  const target = dayjs(until)
  if (!target.isValid()) {
    return String(until)
  }
  const seconds = target.diff(dayjs(), 'second')
  return seconds <= 0 ? '解封处理中' : formatUptime(seconds)
}

/**
 * 从选项反查一句「多久」，给确认框与下拉下方的说明用。
 * 自定义档传进来的已经是换算好的分钟数，所以这里不认 {@link CUSTOM} 这个字符串值。
 */
export function banOptionLabel(value) {
  const hit = BAN_DURATIONS.find((item) => item.value === value)
  return hit ? hit.label : durationText(value)
}
