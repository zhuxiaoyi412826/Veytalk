/**
 * 媒体持久缓存门面（图片 / 视频 / 语音的二进制）。
 *
 * 与消息库完全隔离（规范「缓存隔离」要求）：
 *  - 浏览器：IndexedDB im-media 库，value 直接存 Blob；
 *  - Electron：主进程 userData/cache/media 目录（持久目录，不用系统会自动回收的
 *    临时 cache 位置，防后台静默删文件导致路径失效）。
 *
 * 失效策略（规范「基于 ETag/资源版本号」）：
 *  缓存命中先渲染旧图，后台带 If-None-Match 条件请求复核 —— 服务端 304 就继续用，
 *  返回新内容就替换缓存并刷新界面（stale-while-revalidate）。断网时复核失败，
 *  展示照常，等恢复后再复核，因此离线也能翻看旧图。
 *
 * 配额与 LRU：上限取自设置页 mediaCacheMaxMb（0 = 不限制），超限先淘汰媒体
 *  （最久未访问的在前），消息库本身只有元数据、不参与这轮淘汰。
 */
import { isElectron } from '@/utils/env'
import { mediaGet, mediaPut, mediaDelete, mediaClear, mediaAllSortedByTs } from './localdb/idb'
import { useSettingsStore } from '@/stores/settings'

function bridge() {
  return isElectron() && typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.media
    ? window.__IM_NATIVE__.media
    : null
}

/** 媒体缓存总开关（设置页可关）；读不到 store 时按开启处理 */
function enabled() {
  try {
    return useSettingsStore().mediaCacheEnabled !== false
  } catch {
    return true
  }
}

/** 媒体缓存上限（字节），0 表示不限制；store 还没装好时按默认 200MB 处理 */
function quotaBytes() {
  try {
    const mb = Number(useSettingsStore().mediaCacheMaxMb)
    if (!Number.isFinite(mb) || mb <= 0) {
      return 0
    }
    return mb * 1024 * 1024
  } catch {
    return 200 * 1024 * 1024
  }
}

/** @returns {Promise<{blob: Blob, etag: string}|null>} */
export async function mediaCacheGet(key) {
  if (!enabled()) {
    return null
  }
  const b = bridge()
  if (b) {
    const hit = await b.get(key)
    return hit ? { blob: hit.data, etag: hit.etag } : null
  }
  const row = await mediaGet(key)
  if (!row) {
    return null
  }
  // 命中即 touch：LRU 依据 ts 排序，正在被看的资源不能按“旧”淘汰；失败不影响本次读取
  mediaPut({ ...row, ts: Date.now() }).catch(() => undefined)
  return { blob: row.blob, etag: row.etag }
}

export async function mediaCachePut(key, blob, etag) {
  if (!enabled()) {
    return
  }
  const limit = quotaBytes()
  const b = bridge()
  if (b) {
    await b.put(key, blob, etag || '', (blob && blob.type) || '')
    if (limit > 0) {
      await b.trim(limit)
    }
    return
  }
  await mediaPut({ key, blob, size: (blob && blob.size) || 0, ts: Date.now(), etag: etag || '' })
  if (limit > 0) {
    await trimWebCache(limit)
  }
}

/** 浏览器侧 LRU：按 ts 升序累计，超额部分从头删（getAll 的 Blob 是惰性句柄，不占内存拷贝） */
async function trimWebCache(limit) {
  const rows = await mediaAllSortedByTs()
  let total = rows.reduce((sum, row) => sum + (row.size || 0), 0)
  for (const row of rows) {
    if (total <= limit) {
      break
    }
    total -= row.size || 0
    await mediaDelete(row.key)
  }
}

export async function mediaCacheRemove(key) {
  const b = bridge()
  if (b) {
    return b.remove(key)
  }
  return mediaDelete(key)
}

/** Settings 页「清除媒体缓存」 */
export async function mediaCacheClear() {
  const b = bridge()
  if (b) {
    return b.clear()
  }
  return mediaClear()
}

/** @returns {Promise<{count:number, bytes:number}>} */
export async function mediaCacheStats() {
  const b = bridge()
  if (b) {
    return b.stats()
  }
  const rows = await mediaAllSortedByTs()
  return { count: rows.length, bytes: rows.reduce((sum, row) => sum + (row.size || 0), 0) }
}
