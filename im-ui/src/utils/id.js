/**
 * ID 归一化。
 *
 * 后端的 SafeLongSerializer 按值判断：落在 JS 安全整数范围内的 Long 输出为 JSON 数字
 * （种子用户 1001 就是数字），超出范围的雪花 ID 输出为字符串（19 位）。
 * 于是同一个字段在运行时可能是 number 也可能是 string，直接用 === 比较会漏判：
 * 1001 === '1001' 为 false，表现为「明明是自己的消息却显示在左边」「会话点不中」。
 * 所有跨端 ID 比较与 Map/Set 键都先过 asId。
 */

/** 转成稳定的字符串键，空值统一为 '' */
export function asId(value) {
  return value === null || value === undefined || value === '' ? '' : String(value)
}

/** 两个 ID 是否指向同一实体，任一侧为空即视为不相等 */
export function sameId(a, b) {
  const left = asId(a)
  return left !== '' && left === asId(b)
}

export function isEmptyId(value) {
  return asId(value) === ''
}
