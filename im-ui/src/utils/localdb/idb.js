/**
 * IndexedDB 极简封装（仅浏览器端使用）。
 *
 * 两个相互独立的库：
 *  - im-local：存 sql.js 的整库字节快照（每个用户一条记录，键 = im_user_{id}）。
 *    sql.js 是内存数据库，必须靠这里做落盘快照，页面刷新后才能恢复历史。
 *  - im-media：存图片/视频/文件的 blob 缓存（键 = 资源 URL，by_ts 索引供 LRU 淘汰）。
 *    二进制资源刻意不进 SQLite —— 单个视频就可能撞破 Origin 配额，
 *    混在一起淘汰会把消息库快照一起拖爆（缓存隔离要求）。
 *
 * 不引 idb 等三方库：用到的只有 get/put/delete/clear/按索引全量读五种操作，
 * 手写包装比引入依赖再让构建多打一个包更划算。
 */

const LOCAL_DB = 'im-local'
const LOCAL_STORE = 'db-snapshots'
const MEDIA_DB = 'im-media'
const MEDIA_STORE = 'blobs'

function openDb(name, upgrade) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1)
    request.onupgradeneeded = () => upgrade(request.result)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    request.onblocked = () => reject(new Error('IndexedDB 被其他页面阻塞'))
  })
}

/**
 * 统一的事务执行器：回调拿到 objectStore，事务 complete 后 resolve 请求结果。
 * 用 oncomplete 而不是 request.onsuccess 收口，批量写时才能一次提交。
 */
function withStore(dbPromise, storeName, mode, fn) {
  return dbPromise.then(
    (db) =>
      new Promise((resolve, reject) => {
        let result
        try {
          const t = db.transaction(storeName, mode)
          const request = fn(t.objectStore(storeName))
          if (request && 'result' in request) {
            request.onsuccess = () => {
              result = request.result
            }
          }
          t.oncomplete = () => resolve(result)
          t.onerror = () => reject(t.error)
          t.onabort = () => reject(t.error)
        } catch (error) {
          reject(error)
        }
      })
  )
}

let localDbPromise = null
let mediaDbPromise = null

function localDb() {
  if (!localDbPromise) {
    localDbPromise = openDb(LOCAL_DB, (db) => {
      if (!db.objectStoreNames.contains(LOCAL_STORE)) {
        db.createObjectStore(LOCAL_STORE)
      }
    })
  }
  return localDbPromise
}

function mediaDb() {
  if (!mediaDbPromise) {
    mediaDbPromise = openDb(MEDIA_DB, (db) => {
      if (!db.objectStoreNames.contains(MEDIA_STORE)) {
        db.createObjectStore(MEDIA_STORE, { keyPath: 'key' }).createIndex('by_ts', 'ts')
      }
    })
  }
  return mediaDbPromise
}

/* ------------------------ 整库快照（消息 DB 落盘） ------------------------ */

/** @returns {Promise<Uint8Array|undefined>} 该用户的库字节，首次访问为 undefined */
export function snapshotGet(key) {
  return withStore(localDb(), LOCAL_STORE, 'readonly', (s) => s.get(key))
}

export function snapshotPut(key, bytes) {
  return withStore(localDb(), LOCAL_STORE, 'readwrite', (s) => s.put(bytes, key))
}

export function snapshotDelete(key) {
  return withStore(localDb(), LOCAL_STORE, 'readwrite', (s) => s.delete(key))
}

/* ----------------------------- 媒体 blob 缓存 ----------------------------- */

/** @returns {Promise<{key,blob,size,ts,etag}|undefined>} */
export function mediaGet(key) {
  return withStore(mediaDb(), MEDIA_STORE, 'readonly', (s) => s.get(key))
}

export function mediaPut(value) {
  return withStore(mediaDb(), MEDIA_STORE, 'readwrite', (s) => s.put(value))
}

export function mediaDelete(key) {
  return withStore(mediaDb(), MEDIA_STORE, 'readwrite', (s) => s.delete(key))
}

export function mediaClear() {
  return withStore(mediaDb(), MEDIA_STORE, 'readwrite', (s) => s.clear())
}

/** 全部条目按 ts 升序（最旧在前），LRU 淘汰从头部开始删 */
export function mediaAllSortedByTs() {
  return withStore(mediaDb(), MEDIA_STORE, 'readonly', (s) => s.index('by_ts').getAll())
}
