/**
 * 前端本地缓存（对应后端三级缓存的最前端一层：浏览器 localStorage）。
 *
 * 定位与后端 L1/L2 不同：这里只存「首屏提速用的快照」——好友列表、群列表这类
 * 相对静态的数据。策略是 stale-while-revalidate：进入页面先渲染缓存快照，
 * 后台接口返回后覆盖并写回新快照；实时性数据（未读数、在线状态、消息）
 * 一律以接口/WS 推送为准，不依赖这里的值。
 *
 * 约定：
 * - key 统一加 im.cache. 前缀，与 token 等手工键区分，方便整体排查；
 * - 每条缓存带过期时间（默认 24h），存的时候记、取的时候验，过期即删；
 * - 所有读写 try/catch：无痕模式下 localStorage 会抛异常，缓存永远只能是加速项，
 *   不能成为故障源；
 * - 退出登录必须清掉快照（store 的 reset 里调 removeCache），
 *   否则同一台机器换账号登录会先看到上一个人的列表。
 */

const PREFIX = 'im.cache.'

/** 默认存活 24 小时：快照超过一天宁可空列表慢启，也不要展示过时关系 */
const DEFAULT_TTL_MS = 24 * 60 * 60 * 1000

export function setCache(key, value, ttlMs = DEFAULT_TTL_MS) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ v: value, e: Date.now() + ttlMs }))
  } catch {
    // 配额满 / 无痕模式：放弃缓存，不影响主流程
  }
}

/** 读取未过期的缓存；不存在、过期或解析失败返回 null（过期项顺手删除） */
export function getCache(key) {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed.e !== 'number' || parsed.e < Date.now()) {
      localStorage.removeItem(PREFIX + key)
      return null
    }
    return parsed.v ?? null
  } catch {
    return null
  }
}

export function removeCache(key) {
  try {
    localStorage.removeItem(PREFIX + key)
  } catch {
    // 忽略
  }
}
