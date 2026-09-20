'use strict'
const path = require('path')
const fs = require('fs')
const { ipcMain, app } = require('electron')

/**
 * 主进程本地存储：消息 SQLite + 媒体缓存目录。
 *
 * 架构约束（对应规范）：
 *  - 所有 DB 访问都收敛在主进程，渲染进程经 preload 的 IPC 桥读写，
 *    从根上规避多进程共写一个 sqlite 文件的锁冲突；
 *  - 消息 DB 放 userData/files（持久目录），媒体缓存放 userData/cache/media，
 *    两者物理隔离，清缓存不会误伤消息记录。
 *
 * 引擎选择 sql.js 而非 better-sqlite3：后者要按 Electron 的 ABI 用 node-gyp 重编译
 * （用户机器缺 Python/VS 就装不上），sql.js 是 SQLite 官方编译的 WASM，纯 JS 分发。
 * 代价是它仍是内存库，落盘靠「export → 写临时文件 → rename 原子替换」——
 * rename 在同一文件系统内是原子的，进程崩溃最多回滚到上一份完整快照，
 * 不会写出半截文件，等效于 WAL 的崩溃安全目标（本地缓存丢最近一批写可接受，
 * 服务端才是事实源，下次同步自动补齐）。
 *
 * 启动自检（进程崩溃防护要求）：打开旧库先跑 PRAGMA integrity_check，
 * 失败则把坏文件改名 .corrupt-{ts} 留现场，重建空库 —— 前端发现本地为空会
 * 走服务端增量同步恢复，不需要人工介入。
 */

/* eslint-disable no-unsafe-optional-chaining */
let SQL = null

/** 与前端 utils/localdb/schema.js 保持一致的迁移脚本（主进程是 CJS，无法 import ESM，两处都要改时注意同步） */
const MIGRATIONS = [
  {
    version: 1,
    statements: [
      `CREATE TABLE IF NOT EXISTS messages (
         conv_id       TEXT NOT NULL,
         msg_id        TEXT NOT NULL,
         seq           INTEGER,
         client_msg_id TEXT,
         self          INTEGER NOT NULL DEFAULT 0,
         status        INTEGER,
         recalled      INTEGER NOT NULL DEFAULT 0,
         send_time     TEXT,
         updated_at    INTEGER NOT NULL,
         raw_json      TEXT NOT NULL,
         PRIMARY KEY (conv_id, msg_id)
       )`,
      `CREATE INDEX IF NOT EXISTS idx_messages_conv_seq ON messages (conv_id, seq)`,
      `CREATE INDEX IF NOT EXISTS idx_messages_client ON messages (conv_id, client_msg_id)`,
      `CREATE TABLE IF NOT EXISTS pending_queue (
         client_msg_id TEXT PRIMARY KEY,
         conv_id       TEXT NOT NULL,
         payload       TEXT NOT NULL,
         create_time   INTEGER NOT NULL
       )`
    ]
  }
]
const SCHEMA_VERSION = MIGRATIONS[MIGRATIONS.length - 1].version

/** 当前打开的库：{ userId, db, filePath }；同一时间只属于一个用户 */
let session = null

function filesDir() {
  const dir = path.join(app.getPath('userData'), 'files')
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

function mediaDir() {
  const dir = path.join(app.getPath('userData'), 'cache', 'media')
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

function migrate(db) {
  const rows = db.exec('PRAGMA user_version')
  const current = rows.length && rows[0].values.length ? Number(rows[0].values[0][0]) : 0
  if (current > SCHEMA_VERSION) {
    // 旧客户端打开了新 schema 的库（降级安装）：版本能降，数据形状降不了，重建
    throw new Error(`schema ${current} newer than client ${SCHEMA_VERSION}`)
  }
  for (const migration of MIGRATIONS) {
    if (migration.version > current) {
      for (const statement of migration.statements) db.run(statement)
      db.run(`PRAGMA user_version = ${migration.version}`)
    }
  }
}

/** 原子落盘：先写 .tmp 再 rename，崩溃时旧库保持完整 */
function flush(db, filePath) {
  const bytes = Buffer.from(db.export())
  const tmp = `${filePath}.tmp`
  fs.writeFileSync(tmp, bytes)
  fs.renameSync(tmp, filePath)
}

function openDb(userId) {
  const filePath = path.join(filesDir(), `im_user_${userId}.db`)
  let db
  try {
    db = new SQL.Database(fs.existsSync(filePath) ? fs.readFileSync(filePath) : undefined)
    db.exec('PRAGMA integrity_check')
    migrate(db)
  } catch (error) {
    // 自检失败：坏文件留现场后重建，前端会以「本地为空」为信号走服务端增量同步
    console.warn('[localdb] 本地库损坏/不可用，重建空库:', error && error.message)
    try {
      db && db.close()
    } catch { /* 已经坏了，关不掉就算了 */ }
    if (fs.existsSync(filePath)) {
      try {
        fs.renameSync(filePath, `${filePath}.corrupt-${Date.now()}`)
      } catch { /* 文件被占用时放弃改名，下面用新库覆盖写 */ }
    }
    db = new SQL.Database()
    migrate(db)
  }
  return { db, filePath }
}

/** 关闭当前连接并释放文件句柄（账号切换规范要求：先关旧再开新） */
function closeDb() {
  if (!session) return
  try {
    flush(session.db, session.filePath)
  } catch (error) {
    console.warn('[localdb] 关闭前落盘失败:', error && error.message)
  }
  try {
    session.db.close()
  } catch { /* 已关闭 */ }
  session = null
}

/* ------------------------------ 媒体缓存（目录文件） ------------------------------ */

/** URL → 稳定文件名：djb2 哈希，避免非法字符与超长路径问题 */
function mediaFileFor(key) {
  let hash = 5381
  for (let i = 0; i < key.length; i++) {
    hash = ((hash << 5) + hash + key.charCodeAt(i)) >>> 0
  }
  const hex = hash.toString(16).padStart(8, '0')
  return path.join(mediaDir(), `${hex}-${key.length.toString(16)}.bin`)
}

function mediaMetaFor(file) {
  return `${file}.meta.json`
}

function mediaGet(key) {
  const file = mediaFileFor(String(key))
  if (!fs.existsSync(file)) return null
  let meta = {}
  try {
    meta = JSON.parse(fs.readFileSync(mediaMetaFor(file), 'utf8'))
  } catch { /* 缺 meta 时按无 etag 处理 */ }
  // 命中即 touch（LRU 依据 atime 排序），时间戳写在 meta 里而非文件系统，跨盘可靠
  meta.ts = Date.now()
  try {
    fs.writeFileSync(mediaMetaFor(file), JSON.stringify(meta))
  } catch { /* touch 失败不影响本次读取 */ }
  return { data: fs.readFileSync(file), etag: meta.etag || '', mime: meta.mime || '' }
}

function mediaPut(key, data, etag, mime) {
  const file = mediaFileFor(String(key))
  // IPC 结构化克隆后这里可能是 Buffer / Uint8Array / ArrayBuffer，统一成 Buffer
  const buffer = Buffer.isBuffer(data) ? data : Buffer.from(new Uint8Array(data))
  fs.writeFileSync(file, buffer)
  fs.writeFileSync(mediaMetaFor(file), JSON.stringify({ etag: etag || '', mime: mime || '', ts: Date.now(), size: buffer.length }))
  return true
}

function mediaRemove(key) {
  const file = mediaFileFor(String(key))
  for (const f of [file, mediaMetaFor(file)]) {
    try {
      if (fs.existsSync(f)) fs.unlinkSync(f)
    } catch { /* 已删除 */ }
  }
  return true
}

function mediaEntries() {
  const dir = mediaDir()
  return fs
    .readdirSync(dir)
    .filter((name) => name.endsWith('.bin'))
    .map((name) => {
      const file = path.join(dir, name)
      let meta = {}
      try {
        meta = JSON.parse(fs.readFileSync(`${file}.meta.json`, 'utf8'))
      } catch { /* 缺 meta 按 0 分处理 */ }
      return { file, size: meta.size || fs.statSync(file).size, ts: meta.ts || 0 }
    })
}

function mediaStats() {
  const entries = mediaEntries()
  return { count: entries.length, bytes: entries.reduce((sum, e) => sum + e.size, 0) }
}

function mediaClear() {
  const dir = mediaDir()
  for (const name of fs.readdirSync(dir)) {
    try {
      fs.unlinkSync(path.join(dir, name))
    } catch { /* 正在被引用的文件删不掉就算了 */ }
  }
  return true
}

/** LRU 淘汰到 maxBytes 以下（0 表示不限制）：先淘汰最久没被访问的 */
function mediaTrim(maxBytes) {
  const limit = Number(maxBytes) || 0
  if (limit <= 0) return mediaStats()
  const entries = mediaEntries().sort((a, b) => a.ts - b.ts)
  let total = entries.reduce((sum, e) => sum + e.size, 0)
  for (const entry of entries) {
    if (total <= limit) break
    total -= entry.size
    for (const f of [entry.file, `${entry.file}.meta.json`]) {
      try {
        if (fs.existsSync(f)) fs.unlinkSync(f)
      } catch { /* 忽略 */ }
    }
  }
  return { count: entries.length, bytes: total }
}

/* ---------------------------------- IPC ---------------------------------- */

/**
 * 注册本地存储 IPC。渲染进程桥在 preload.js 的 __IM_NATIVE__.db / .media。
 * 每个 handler 都包 try/catch 返回 { ok, data|error }：DB 异常如果直抛，
 * preload 侧只会拿到一句「Error invoking remote method」，排查时看不到根因。
 */
function setupLocalDb() {
  const wrap = (fn) => async (event, payload) => {
    try {
      return { ok: true, data: await fn(payload) }
    } catch (error) {
      console.warn('[localdb] IPC 执行失败:', error && error.message)
      return { ok: false, error: String((error && error.message) || error) }
    }
  }

  ipcMain.handle(
    'im:db-open',
    wrap(async ({ userId }) => {
      if (!SQL) {
        // require('sql.js') 返回 initSqlJs 工厂，Node 环境下自己从文件系统加载 wasm
        SQL = await require('sql.js')()
      }
      if (session && String(session.userId) === String(userId)) {
        return session.filePath
      }
      // 账号切换：必须先关旧连接释放句柄，再开新库
      closeDb()
      const { db, filePath } = openDb(userId)
      session = { userId: String(userId), db, filePath }
      return filePath
    })
  )

  ipcMain.handle(
    'im:db-close',
    wrap(() => {
      closeDb()
      return true
    })
  )

  ipcMain.handle(
    'im:db-all',
    wrap(({ sql, params }) => {
      if (!session) throw new Error('db not opened')
      const statement = session.db.prepare(sql)
      try {
        statement.bind(params || [])
        const rows = []
        while (statement.step()) rows.push(statement.getAsObject())
        return rows
      } finally {
        statement.free()
      }
    })
  )

  ipcMain.handle(
    'im:db-write',
    wrap(({ statements }) => {
      if (!session) throw new Error('db not opened')
      session.db.run('BEGIN')
      try {
        for (const [sql, params] of statements || []) {
          session.db.run(sql, params || [])
        }
        session.db.run('COMMIT')
      } catch (error) {
        try {
          session.db.run('ROLLBACK')
        } catch { /* 事务已断 */ }
        throw error
      }
      flush(session.db, session.filePath)
      return true
    })
  )

  ipcMain.handle(
    'im:db-info',
    wrap(() => {
      if (!session) return { messages: 0, backend: 'electron' }
      const rows = session.db.exec('SELECT COUNT(*) FROM messages')
      const messages = rows.length && rows[0].values.length ? Number(rows[0].values[0][0]) : 0
      return { messages, backend: 'electron' }
    })
  )

  ipcMain.handle(
    'im:db-destroy',
    wrap(() => {
      if (!session) return true
      const { db, filePath } = session
      session = null
      try {
        db.close()
      } catch { /* 已关闭 */ }
      for (const f of [filePath, `${filePath}.tmp`]) {
        try {
          if (fs.existsSync(f)) fs.unlinkSync(f)
        } catch { /* 占用时下次启动再清 */ }
      }
      return true
    })
  )

  ipcMain.handle('im:media-get', wrap(({ key }) => mediaGet(key)))
  ipcMain.handle('im:media-put', wrap(({ key, data, etag, mime }) => mediaPut(key, data, etag, mime)))
  ipcMain.handle('im:media-remove', wrap(({ key }) => mediaRemove(key)))
  ipcMain.handle('im:media-clear', wrap(() => mediaClear()))
  ipcMain.handle('im:media-stats', wrap(() => mediaStats()))
  ipcMain.handle('im:media-trim', wrap(({ maxBytes }) => mediaTrim(maxBytes)))

  app.on('before-quit', () => {
    closeDb()
  })
}

module.exports = { setupLocalDb }
