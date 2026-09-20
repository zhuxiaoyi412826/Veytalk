/**
 * 浏览器端驱动：sql.js（SQLite 编译为 WASM）+ IndexedDB 字节快照。
 *
 * sql.js 是纯内存数据库，本驱动的职责是把它伪装成一个可持久化的存储：
 *  1. open 时从 IndexedDB 读上次的整库字节 import，没有则新建；
 *  2. write 在内存库里跑完一批语句后 export 字节数组回写 IndexedDB；
 *  3. 多 Tab 冲突（同一浏览器开多个页面读写同一个库）：
 *     - 写路径用 Web Locks（navigator.locks）串行化，两个 Tab 不会同时改快照；
 *     - 其他 Tab 写完通过 BroadcastChannel 广播 saved，本 Tab 标记 stale，
 *       下一次操作前重新 import 最新快照 —— 最终一致，不需要跨 Tab 实时同步游标。
 *  4. 后台页签冻结风险：写事务全部拆成「单批语句 + 立即落盘」的小步，
 *     不存在跨事件循环挂起的大事务，页面被冻结时最多丢一批尚未落盘的写（内存态仍在）。
 *
 * 配额（Origin 通常几十 MB~几百 MB）由上层 medacache 的 LRU 控制媒体侧，
 * 消息库只存元数据，正常用不到配额上限。
 */
import initSqlJs from 'sql.js'
import wasmUrl from 'sql.js/dist/sql-wasm.wasm?url'
import { MIGRATIONS, SCHEMA_VERSION, dbKeyFor } from './schema'
import { snapshotGet, snapshotPut, snapshotDelete } from './idb'

let SQL = null

async function loadSql() {
  if (!SQL) {
    SQL = await initSqlJs({ locateFile: () => wasmUrl })
  }
  return SQL
}

/** 本 Tab 的随机身份，广播时用来忽略自己发的消息 */
const TAB_ID = `t-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`

export class WebDriver {
  constructor() {
    /** @type {any} sql.js Database */
    this.db = null
    this.key = ''
    this.dirty = false
    this.stale = false
    /** 串行化所有操作的 promise 链：sql.js 非并发安全，队列比锁更省事且顺序确定 */
    this.chain = Promise.resolve()
    this.channel = null
  }

  async open(userId) {
    await loadSql()
    this.key = dbKeyFor(userId)
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel(`im-localdb-${this.key}`)
      this.channel.onmessage = (event) => {
        if (event.data && event.data.tab !== TAB_ID && event.data.type === 'saved') {
          // 别的 Tab 写了新快照：本 Tab 内存里的库过期了，下次操作前重读。
          // 自己有未落盘的脏数据时先落盘再重载，双 Tab 的写都不会丢。
          this.stale = true
        }
      }
    }
    await this._load()
  }

  async _load() {
    const bytes = await snapshotGet(this.key)
    this.db = new SQL.Database(bytes || undefined)
    this.dirty = false
    this.stale = false
    this._migrate()
  }

  /** 逐版执行迁移脚本，版本号记在 PRAGMA user_version 里 */
  _migrate() {
    const current = this._pragmaVersion()
    if (current > SCHEMA_VERSION) {
      // 更旧的客户端打开了新 schema 的库（降级安装）：直接弃库，让服务端同步恢复，
      // 拿旧迁移脚本硬兼容的成本远高于一次增量同步
      this._rebuild()
      return
    }
    for (const migration of MIGRATIONS) {
      if (migration.version > current) {
        for (const statement of migration.statements) {
          this.db.run(statement)
        }
        this.db.run(`PRAGMA user_version = ${migration.version}`)
      }
    }
  }

  _pragmaVersion() {
    const rows = this._rawAll('PRAGMA user_version')
    return rows.length && Number(rows[0].user_version) || 0
  }

  _rebuild() {
    try {
      this.db.close()
    } catch {
      // 已经处于坏状态，关掉失败不影响重建
    }
    this.db = new SQL.Database()
    this._migrate()
  }

  /** 底层同步查询（不走队列），内部也用于 PRAGMA */
  _rawAll(sql, params = []) {
    const statement = this.db.prepare(sql)
    try {
      statement.bind(params)
      const rows = []
      while (statement.step()) {
        rows.push(statement.getAsObject())
      }
      return rows
    } finally {
      statement.free()
    }
  }

  /** 排进队列执行一个任务：所有公开方法都经它串行化 */
  _queue(task) {
    const run = this.chain.then(task, task)
    // 链上吞掉错误，避免一次失败把后续所有操作全断掉；错误照常抛给本次调用方
    this.chain = run.then(() => undefined, () => undefined)
    return run
  }

  async all(sql, params = []) {
    return this._queue(async () => {
      await this._refreshIfStale()
      return this._rawAll(sql, params)
    })
  }

  /**
   * 一批语句在一个隐式事务语义下执行（全部成功才算成功），完成后落盘。
   * @param statements [[sql, params], ...]
   */
  async write(statements) {
    return this._queue(async () => {
      await this._refreshIfStale()
      this.db.run('BEGIN')
      try {
        for (const [sql, params] of statements) {
          this.db.run(sql, params || [])
        }
        this.db.run('COMMIT')
      } catch (error) {
        try {
          this.db.run('ROLLBACK')
        } catch {
          // ROLLBACK 也失败说明库已损坏，交给下面的弃库处理
        }
        this._rebuild()
        throw error
      }
      this.dirty = true
      await this._save()
    })
  }

  /** 有最新快照就把内存里未保存的改动先落盘，再重载 —— stale 收敛策略 */
  async _refreshIfStale() {
    if (!this.stale) {
      return
    }
    if (this.dirty) {
      await this._save()
    }
    await this._load()
  }

  /** 互斥落盘：跨 Tab 用 Web Locks 保证「export → put」不交错 */
  async _save() {
    const bytes = this.db.export()
    const writeTask = () => snapshotPut(this.key, bytes).then(() => {
      this.dirty = false
      if (this.channel) {
        this.channel.postMessage({ type: 'saved', tab: TAB_ID })
      }
    })
    if (typeof navigator !== 'undefined' && navigator.locks && navigator.locks.request) {
      await navigator.locks.request(`im-localdb-${this.key}`, writeTask)
    } else {
      await writeTask()
    }
  }

  async info() {
    return this._queue(async () => {
      const rows = this._rawAll('SELECT COUNT(*) AS c FROM messages')
      return { messages: rows[0] ? Number(rows[0].c) : 0, media: -1, backend: 'web' }
    })
  }

  async close() {
    await this._queue(async () => {
      if (this.dirty) {
        await this._save()
      }
      if (this.db) {
        this.db.close()
        this.db = null
      }
      if (this.channel) {
        this.channel.close()
        this.channel = null
      }
    })
  }

  /** 弃掉当前用户的整库（清空消息缓存 / 账号切换的旧库保留给下次登录） */
  async destroy() {
    await this.close()
    await snapshotDelete(this.key)
  }
}
