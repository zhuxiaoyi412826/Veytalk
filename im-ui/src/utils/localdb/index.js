/**
 * 本地消息库门面：chat store 唯一需要认识的入口。
 *
 * 按宿主自动选择驱动：
 *  - Electron：IPC 转发给主进程的 better-sqlite3 / sql.js（渲染进程不碰库文件）；
 *  - 浏览器：sql.js WASM + IndexedDB 快照（driver-web）。
 *
 * 整体是可缺省的优化层：驱动初始化失败（隐私模式禁 IDB、WASM 加载失败、
 * 老版本 Electron 没有桥）时 enabled 置 false，所有方法变成「查不到 / 写不进」的空操作，
 * 聊天功能照常工作，只是退化成每次从服务端拉历史 —— 缓存挂了不能把应用一起拖死。
 */
import { isElectron } from '@/utils/env'
import { asId } from '@/utils/id'
import { UPSERT_MESSAGE, SELECT_RECENT, SELECT_BEFORE, localMsgId } from './schema'
import { WebDriver } from './driver-web'
import { ElectronDriver } from './driver-electron'

let driver = null
let enabled = false
let currentUserId = null

/** 包一层「驱动必须已就绪」的执行器：未启用时返回 fallback，异常只记日志不向上抛 */
function guard(fn, fallback) {
  if (!enabled || !driver) {
    return Promise.resolve(fallback)
  }
  return fn().catch((error) => {
    console.warn('[localdb] 本地库操作失败，已降级为服务端直读', error)
    return fallback
  })
}

/** 本地库是否可用（Settings 页据此提示缓存管理是否生效） */
export function localDbEnabled() {
  return enabled
}

/**
 * 登录后调用；账号切换时先关闭上一个用户的连接再开新库
 * （规范要求：防句柄泄漏、防读写错乱）。幂等，可重复调用。
 */
export async function initLocalDb(userId) {
  const id = asId(userId)
  if (!id) {
    return
  }
  if (currentUserId === id && enabled) {
    return
  }
  await closeLocalDb()
  try {
    const bridgeReady = isElectron() && typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.db
    driver = bridgeReady ? new ElectronDriver() : new WebDriver()
    await driver.open(id)
    currentUserId = id
    enabled = true
  } catch (error) {
    console.warn('[localdb] 初始化失败，本地缓存不可用（功能不受影响，历史走服务端）', error)
    driver = null
    enabled = false
  }
}

/** 登出 / 被踢 / 切换账号：落盘并断开当前用户的库 */
export function closeLocalDb() {
  if (!driver) {
    return Promise.resolve()
  }
  const closing = driver
  driver = null
  enabled = false
  currentUserId = null
  return closing.close().catch(() => undefined)
}

/* ------------------------------- 消息读写 ------------------------------- */

function messageToParams(convId, message) {
  const msgId = message.messageId ? String(message.messageId) : localMsgId(message.clientMsgId)
  const seq = Number(message.seq)
  return [
    asId(convId),
    msgId,
    Number.isFinite(seq) ? seq : null,
    message.clientMsgId ? String(message.clientMsgId) : null,
    message.self ? 1 : 0,
    message.status === undefined || message.status === null ? null : Number(message.status),
    message.recalled ? 1 : 0,
    message.sendTime || null,
    Date.now(),
    JSON.stringify(message)
  ]
}

/** 服务端权威消息落库时，顺手删掉同 clientMsgId 的本地占位行（msgId 合并） */
function placeholderDeleteParams(convId, message) {
  if (!message.messageId || !message.clientMsgId) {
    return null
  }
  return [asId(convId), localMsgId(message.clientMsgId)]
}

/** 批量写穿：chat store 每次合并/更新后把变化的消息同步进来 */
export function dbUpsertMessages(conversationId, messages) {
  const list = (messages || []).filter((m) => m && (m.messageId || m.clientMsgId))
  if (!list.length) {
    return Promise.resolve(false)
  }
  return guard(async () => {
    const statements = []
    for (const message of list) {
      statements.push([UPSERT_MESSAGE, messageToParams(conversationId, message)])
      const del = placeholderDeleteParams(conversationId, message)
      if (del) {
        statements.push(['DELETE FROM messages WHERE conv_id = ? AND msg_id = ?', del])
      }
    }
    await driver.write(statements)
    return true
  }, false)
}

export function dbUpsertMessage(conversationId, message) {
  return dbUpsertMessages(conversationId, [message])
}

function parseRows(rows) {
  const result = []
  for (const row of rows || []) {
    try {
      result.push(JSON.parse(row.raw_json))
    } catch {
      // 单行损坏不能连累整页历史，跳过即可（下次服务端拉取会修好）
    }
  }
  return result
}

/** 进入会话：本地最新 N 条（升序返回），拿到即可先渲染，不等网络 */
export function dbLoadRecent(conversationId, limit) {
  return guard(async () => {
    const rows = await driver.all(SELECT_RECENT, [asId(conversationId), limit])
    return parseRows(rows).reverse()
  }, [])
}

/** 向上滚动：本地游标页（升序返回）；返回条数不足由调用方去服务端补齐 */
export function dbLoadBefore(conversationId, beforeSeq, limit) {
  const seq = Number(beforeSeq)
  if (!Number.isFinite(seq)) {
    return Promise.resolve([])
  }
  return guard(async () => {
    const rows = await driver.all(SELECT_BEFORE, [asId(conversationId), seq, limit])
    return parseRows(rows).reverse()
  }, [])
}

/** 单端删除：删本地行（服务端删除接口成功后调用） */
export function dbDeleteMessage(conversationId, messageId) {
  return guard(async () => {
    await driver.write([['DELETE FROM messages WHERE conv_id = ? AND msg_id = ?', [asId(conversationId), String(messageId)]]])
    return true
  }, false)
}

/** 丢弃未落库的本地占位（发送失败后用户手动删除） */
export function dbDiscardLocal(conversationId, clientMsgId) {
  return guard(async () => {
    await driver.write([['DELETE FROM messages WHERE conv_id = ? AND msg_id = ?', [asId(conversationId), localMsgId(clientMsgId)]]])
    return true
  }, false)
}

/** 清空某会话的本地消息（服务端「清空聊天记录」后调用，防复活） */
export function dbClearConversation(conversationId) {
  return guard(async () => {
    await driver.write([['DELETE FROM messages WHERE conv_id = ?', [asId(conversationId)]]])
    return true
  }, false)
}

/** 清空全部消息缓存（Settings 的「清除消息缓存」；保留待发送队列，那是未完成的承诺） */
export function dbClearAllMessages() {
  return guard(async () => {
    await driver.write([['DELETE FROM messages', []]])
    return true
  }, false)
}

/** 导出当前用户的全部本地消息：[[convId, rawJson], ...] */
export function dbExportAll() {
  return guard(async () => {
    const rows = await driver.all('SELECT conv_id, raw_json FROM messages ORDER BY conv_id, COALESCE(seq, 0)', [])
    return rows.map((row) => {
      try {
        return [row.conv_id, JSON.parse(row.raw_json)]
      } catch {
        return null
      }
    }).filter(Boolean)
  }, [])
}

/* ------------------------------ 离线待发送队列 ------------------------------ */

/**
 * 断网时入队。payload 是 messageApi.sendMessage 的完整参数（含 clientMsgId），
 * 网络恢复后原样重提交 —— clientMsgId 是幂等键，服务端按它去重，不会重复入库。
 */
export function dbEnqueuePending(payload) {
  if (!payload || !payload.clientMsgId) {
    return Promise.resolve(false)
  }
  return guard(async () => {
    await driver.write([[
      'INSERT INTO pending_queue (client_msg_id, conv_id, payload, create_time) VALUES (?,?,?,?) ' +
      'ON CONFLICT(client_msg_id) DO UPDATE SET payload = excluded.payload',
      [String(payload.clientMsgId), asId(payload.conversationId), JSON.stringify(payload), Date.now()]
    ]])
    return true
  }, false)
}

/** 队列按创建时间升序：保持消息的发送次序，恢复后不能倒着补 */
export function dbListPending() {
  return guard(async () => {
    const rows = await driver.all('SELECT client_msg_id, payload FROM pending_queue ORDER BY create_time', [])
    return rows.map((row) => {
      try {
        return JSON.parse(row.payload)
      } catch {
        return null
      }
    }).filter(Boolean)
  }, [])
}

export function dbRemovePending(clientMsgId) {
  return guard(async () => {
    await driver.write([['DELETE FROM pending_queue WHERE client_msg_id = ?', [String(clientMsgId)]]])
    return true
  }, false)
}

/* --------------------------------- 统计 --------------------------------- */

/** @returns {Promise<{messages:number, pending:number, backend:string}> */
export function dbStats() {
  return guard(async () => {
    const info = await driver.info()
    const pending = await driver.all('SELECT COUNT(*) AS c FROM pending_queue', [])
    return {
      messages: Number(info.messages) || 0,
      pending: pending.length ? Number(pending[0].c) : 0,
      backend: info.backend || (isElectron() ? 'electron' : 'web')
    }
  }, { messages: 0, pending: 0, backend: 'disabled' })
}
