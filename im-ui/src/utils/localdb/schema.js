/**
 * 本地 SQLite 的 schema 与版本迁移脚本。
 *
 * 浏览器端（sql.js WASM）与 Electron 主进程端共用同一套语句，
 * 保证两端表结构一致，切换宿主时数据形状可预期。
 *
 * 表设计：结构化列（seq/status/recalled...）负责查询、排序与合并判断，
 * raw_json 存归一化后的完整消息对象负责无损恢复 —— 后端 VO 加了新字段
 * 不必立刻改本地表，老版本客户端读到未知字段也只是忽略。
 *
 * 迁移约定（对应「DB 版本迁移」需求）：
 *  - MIGRATIONS 数组的下标 +1 即 schema 版本，只允许在末尾追加，禁止改动历史项；
 *  - 当前版本记录在 PRAGMA user_version 里，启动时逐版升级到最新；
 *  - 升级失败（进程被杀/存储被清等极端场景）时驱动层弃库重建，
 *    随后由服务端增量同步恢复本地数据 —— 本地缓存永远只是缓存，不是唯一事实源。
 */

/** 每个用户一个独立库：浏览器存 IndexedDB 的键、Electron 落盘的文件名都以此为前缀 */
export function dbKeyFor(userId) {
  return `im_user_${userId}`
}

/** 本地占位（还没拿到服务端 messageId）的主键前缀，落库合并后由 upsert 顺手删掉 */
export function localMsgId(clientMsgId) {
  return `local:${clientMsgId}`
}

export const MIGRATIONS = [
  {
    version: 1,
    statements: [
      // 消息元数据表：二进制附件一律不进库（只存服务端 URL / 文件 ID），
      // 这是「缓存隔离」要求 —— 媒体与消息分开存放，互不牵连配额
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
      // 游标翻页（seq < beforeSeq）与按会话清空都靠这个复合索引
      `CREATE INDEX IF NOT EXISTS idx_messages_conv_seq ON messages (conv_id, seq)`,
      // 服务端落库消息与本地占位靠 client_msg_id 归并
      `CREATE INDEX IF NOT EXISTS idx_messages_client ON messages (conv_id, client_msg_id)`,
      // 离线待发送队列：断网时入队，恢复后按 create_time 顺序提交
      `CREATE TABLE IF NOT EXISTS pending_queue (
         client_msg_id TEXT PRIMARY KEY,
         conv_id       TEXT NOT NULL,
         payload       TEXT NOT NULL,
         create_time   INTEGER NOT NULL
       )`
    ]
  }
]

/** 迁移脚本能升到的最新版本，两端驱动都从这里取 */
export const SCHEMA_VERSION = MIGRATIONS[MIGRATIONS.length - 1].version

/**
 * 单条消息的 upsert：
 *  - 结构化列用 COALESCE/MAX 做「增量打补丁」（传 null 表示该列不变，状态不回退）；
 *  - raw_json 整体覆盖 —— 调用方（chat store）已在内存里完成合并，写回的总是最新全貌。
 */
export const UPSERT_MESSAGE = `
  INSERT INTO messages (conv_id, msg_id, seq, client_msg_id, self, status, recalled, send_time, updated_at, raw_json)
  VALUES (?,?,?,?,?,?,?,?,?,?)
  ON CONFLICT(conv_id, msg_id) DO UPDATE SET
    seq           = COALESCE(excluded.seq, messages.seq),
    client_msg_id = COALESCE(excluded.client_msg_id, messages.client_msg_id),
    self          = MAX(excluded.self, messages.self),
    status        = COALESCE(excluded.status, messages.status),
    recalled      = MAX(excluded.recalled, messages.recalled),
    send_time     = COALESCE(excluded.send_time, messages.send_time),
    updated_at    = excluded.updated_at,
    raw_json      = excluded.raw_json`

/**
 * 读取某会话最新的 N 条（DESC 取尾再反转，占位消息 seq 为 NULL 排最大、天然进最新段）。
 * 第二个参数同时当「NULL seq 视为无穷大」的哨兵值用，避免 CASE 表达式影响索引选择。
 */
export const SELECT_RECENT = `
  SELECT raw_json FROM messages
  WHERE conv_id = ?
  ORDER BY COALESCE(seq, 9223372036854775807) DESC
  LIMIT ?`

/** 游标翻页：seq < beforeSeq 的更早一页（本地缺时由调用方回落到服务端拉取并回写） */
export const SELECT_BEFORE = `
  SELECT raw_json FROM messages
  WHERE conv_id = ? AND seq < ?
  ORDER BY seq DESC
  LIMIT ?`
