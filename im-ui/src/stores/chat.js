import { defineStore } from 'pinia'
import * as messageApi from '@/api/message'
import { asId, sameId } from '@/utils/id'
import { isOpen as wsOpen, send as wsSend } from '@/ws/socket'
import { useAuthStore } from './auth'

/**
 * 聊天消息：当前会话的消息列表、发送中的本地消息、历史分页游标与本地缓存。
 *
 * 消息按会话分桶存放（键是字符串化的会话 ID），切换会话不清空已加载的数据，
 * 来回切换时不必重新拉一次历史。
 */

/** 历史分页每页条数，与后端默认值一致（上限 100） */
const PAGE_SIZE = 20

/** 本地缓存每个会话保留的条数：localStorage 只有 5MB 左右，全量缓存几次就满了 */
const CACHE_LIMIT = 100

const CACHE_PREFIX = 'im_msgs_'

/** 消息状态：与后端 MessageStatus 枚举一一对应 */
const STATUS_TEXT = {
  0: '发送中',
  1: '已发送',
  2: '已送达',
  3: '已读',
  4: '已撤回',
  5: '发送失败'
}

/** 附件消息在气泡里显示的类型名，msgTypeDesc 缺失时兜底 */
const TYPE_TEXT = { 1: '文本', 2: '图片', 3: '文件', 4: '语音', 5: '系统通知' }

function seqOf(message) {
  const value = Number(message && message.seq)
  return Number.isFinite(value) ? value : null
}

function timeOf(message) {
  const value = new Date(message && message.sendTime).getTime()
  return Number.isNaN(value) ? 0 : value
}

/**
 * 消息排序：按 seq 升序。
 *
 * 本地刚创建、还没拿到 seq 的消息一律排在最后 —— 它就是最新的一条。
 * 不能靠 sendTime 排：客户端时钟可能比服务端慢几分钟，
 * 那样刚发出的消息会被插到历史消息中间去。
 */
function compareMessages(a, b) {
  const left = seqOf(a)
  const right = seqOf(b)
  if (left !== null && right !== null) {
    return left - right
  }
  if (left === null && right === null) {
    return timeOf(a) - timeOf(b)
  }
  return left === null ? 1 : -1
}

/** 客户端消息 ID：幂等键，重复提交同一个 ID 后端返回首次结果 */
export function newClientMsgId() {
  return `c-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function statusText(status) {
  return STATUS_TEXT[Number(status)] || ''
}

/**
 * 把 HTTP 的 MessageVO 与 WebSocket 的 MessageDTO 归一成同一种形状。
 *
 * DTO 比 VO 少 self / readCount / statusDesc / msgTypeDesc / recallTime，
 * 而气泡渲染要靠 self 决定左右、靠 statusDesc 显示状态，缺了就得在每个组件里各写一遍判断。
 */
function normalizeMessage(raw, fallbackSelf) {
  if (!raw) {
    return null
  }
  return {
    ...raw,
    self: raw.self === undefined || raw.self === null ? !!fallbackSelf : raw.self,
    status: raw.status === undefined || raw.status === null ? 1 : raw.status,
    statusDesc: raw.statusDesc || statusText(raw.status),
    msgTypeDesc: raw.msgTypeDesc || TYPE_TEXT[Number(raw.msgType)] || '',
    recalled: raw.recalled === true || Number(raw.status) === 4
  }
}

function readCache(conversationId) {
  try {
    const text = localStorage.getItem(CACHE_PREFIX + asId(conversationId))
    if (!text) {
      return []
    }
    const parsed = JSON.parse(text)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    // 缓存被写坏（旧版本结构、超配额截断）时直接丢弃，不能让聊天窗口打不开
    return []
  }
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    /** { [convId]: MessageVO[] } */
    messages: {},
    /** { [convId]: boolean } 是否还有更早的历史 */
    hasMore: {},
    /** { [convId]: boolean } 正在拉历史，防止滚动触顶时并发重复请求 */
    loadingHistory: {},
    /** 离线消息是否已经拉过一次，重连时用它避免重复拉取 */
    offlinePulled: false
  }),

  getters: {
    listOf: (state) => (conversationId) => state.messages[asId(conversationId)] || [],
    hasMoreOf: (state) => (conversationId) => !!state.hasMore[asId(conversationId)],
    isLoadingOf: (state) => (conversationId) => !!state.loadingHistory[asId(conversationId)]
  },

  actions: {
    bucket(conversationId) {
      const key = asId(conversationId)
      if (!this.messages[key]) {
        this.messages[key] = []
      }
      return key
    },

    /**
     * 加载历史消息。
     *
     * @param reset true 表示进入会话时的首次加载（取最新一页并与本地缓存合并），
     *              false 表示向上滚动加载更早的一页
     */
    async loadHistory(conversationId, reset = false) {
      const key = this.bucket(conversationId)
      if (this.loadingHistory[key]) {
        return this.messages[key]
      }
      this.loadingHistory[key] = true
      try {
        const current = this.messages[key]
        const beforeSeq = reset ? null : (current.length ? seqOf(current[0]) : null)
        if (!reset && beforeSeq === null) {
          this.hasMore[key] = false
          return current
        }
        const page = (await messageApi.fetchHistory(conversationId, beforeSeq, PAGE_SIZE)) || []
        const normalized = page.map((item) => normalizeMessage(item, false))
        if (reset) {
          // 本地缓存与服务端这一页合并去重：缓存里可能有刚发出还没落库的本地消息，
          // 也可能有上次会话看到的更早内容，两者都不能直接丢掉
          this.messages[key] = dedupe([...readCache(conversationId).map((m) => normalizeMessage(m, m.self)), ...normalized])
          this.hasMore[key] = page.length >= PAGE_SIZE
        } else {
          this.messages[key] = dedupe([...normalized, ...current])
          this.hasMore[key] = page.length >= PAGE_SIZE
        }
        this.messages[key].sort(compareMessages)
        persist(key, this.messages[key])
        return this.messages[key]
      } finally {
        this.loadingHistory[key] = false
      }
    },

    /**
     * 追加一条消息，按 messageId / clientMsgId 去重。
     *
     * 去重是必需的：HTTP 发送的响应与 WebSocket 的 ack 是同一条消息的两个来源，
     * 谁先到不确定；重连后拉离线消息也会把已经收到的消息再给一遍。
     */
    appendMessage(conversationId, raw, fallbackSelf = false) {
      const message = normalizeMessage(raw, fallbackSelf)
      if (!message) {
        return null
      }
      const key = this.bucket(conversationId)
      const list = this.messages[key]
      const existing = findMessage(list, message)
      if (existing) {
        // 已有的是本地占位（无 messageId），来的是服务端权威数据，就地补全而不是替换整条：
        // 替换会让 Vue 认为是新对象，气泡会重放一次进入动画
        Object.assign(existing, message)
        list.sort(compareMessages)
        persist(key, list)
        return existing
      }
      list.push(message)
      list.sort(compareMessages)
      persist(key, list)
      return message
    },

    /**
     * 发送一条消息（HTTP 通道）。
     *
     * 先塞一条本地占位（status=0 发送中）再发请求，用户按下回车立刻就能看到气泡。
     * 失败时不删除占位，而是标成 status=5，让用户能看见「这条没发出去」并重试。
     */
    async send(conversationId, { msgType, content, extra, atAll, atUserIds, clientMsgId, quoteMsgId }) {
      const auth = useAuthStore()
      const id = clientMsgId || newClientMsgId()
      const now = new Date().toISOString()
      this.appendMessage(
        conversationId,
        {
          clientMsgId: id,
          conversationId,
          fromUserId: auth.userId,
          fromNickname: auth.nickname,
          fromAvatar: auth.avatarRaw,
          msgType,
          content,
          extra,
          quoteMsgId: quoteMsgId || null,
          seq: null,
          status: 0,
          recalled: false,
          sendTime: now
        },
        true
      )
      try {
        const vo = await messageApi.sendMessage({
          clientMsgId: id,
          msgType,
          content,
          conversationId,
          atAll,
          atUserIds,
          extra,
          quoteMsgId: quoteMsgId || undefined
        })
        this.appendMessage(conversationId, vo, true)
        return vo
      } catch (error) {
        this.markFailed(conversationId, id)
        throw error
      }
    },

    sendText(conversationId, text) {
      return this.send(conversationId, { msgType: 1, content: text })
    },

    /**
     * 发送附件消息。
     *
     * content 传文件 ID，服务端按文件记录回填元数据；width / height 服务端不回填，
     * 由调用方在上传前读出来放进 extra，否则缩略图会在加载完成的瞬间跳一下。
     */
    sendAttachment(conversationId, { msgType, fileId, extra }) {
      return this.send(conversationId, { msgType, content: String(fileId), extra: { ...extra, fileId } })
    },

    markFailed(conversationId, clientMsgId) {
      const key = this.bucket(conversationId)
      const target = this.messages[key].find((item) => sameId(item.clientMsgId, clientMsgId) && !item.messageId)
      if (target) {
        target.status = 5
        target.statusDesc = STATUS_TEXT[5]
        persist(key, this.messages[key])
      }
    },

    /** 重发一条失败的消息：复用原来的 clientMsgId，后端幂等会直接返回首次结果 */
    async resend(conversationId, message) {
      if (!message || !message.clientMsgId) {
        return null
      }
      const key = this.bucket(conversationId)
      const target = this.messages[key].find((item) => sameId(item.clientMsgId, message.clientMsgId))
      if (target) {
        target.status = 0
        target.statusDesc = STATUS_TEXT[0]
      }
      try {
        const vo = await messageApi.sendMessage({
          clientMsgId: message.clientMsgId,
          msgType: message.msgType,
          content: message.content,
          conversationId,
          extra: message.extra,
          quoteMsgId: message.quoteMsgId || undefined
        })
        this.appendMessage(conversationId, vo, true)
        return vo
      } catch (error) {
        this.markFailed(conversationId, message.clientMsgId)
        throw error
      }
    },

    /** 丢弃一条发送失败的本地消息（它从未落库，删除只是清掉占位） */
    discard(conversationId, clientMsgId) {
      const key = this.bucket(conversationId)
      this.messages[key] = this.messages[key].filter((item) => !sameId(item.clientMsgId, clientMsgId))
      persist(key, this.messages[key])
    },

    /**
     * 转发消息到目标会话。
     *
     * 转发复用原消息的文件 ID，不重新上传；附件消息由服务端校验转发者是否属于原会话成员。
     */
    async forward(conversationId, messageId) {
      const vo = await messageApi.forwardMessage({ conversationId, messageId })
      this.appendMessage(conversationId, vo, true)
      return vo
    },

    /**
     * 处理 ack 帧。
     *
     * ack 推给发送者的全部设备端，且 clientMsgId 在报文外层而不在 data 里。
     * 本地找不到这个 clientMsgId 时说明消息是在自己的另一台设备上发的，
     * 当作新消息追加即可 —— 多端同步不需要额外协议。
     * 两种情形走的是同一条 appendMessage，去重逻辑会把「HTTP 响应先到」与「ack 先到」两种顺序都收敛成一条。
     */
    applyAck(clientMsgId, dto) {
      if (!dto) {
        return
      }
      return this.appendMessage(dto.conversationId, { ...dto, clientMsgId: clientMsgId || dto.clientMsgId }, true)
    },

    applyIncoming(dto) {
      if (!dto) {
        return
      }
      return this.appendMessage(dto.conversationId, dto, false)
    },

    /** 撤回通知：data = { messageId, conversationId, operatorId, summary } */
    applyRecall(payload) {
      if (!payload) {
        return
      }
      const key = this.bucket(payload.conversationId)
      const target = this.messages[key].find((item) => sameId(item.messageId, payload.messageId))
      if (target) {
        target.recalled = true
        target.status = 4
        target.statusDesc = STATUS_TEXT[4]
        target.recallSummary = payload.summary || ''
        persist(key, this.messages[key])
      }
    },

    /**
     * 送达 / 已读回执：data = { messageIds, userId, timestamp }，推给原发送者。
     *
     * 只在自己发出的消息上升级状态，且状态只能往前走 ——
     * 回执到达的顺序不保证（已读可能比送达先到），退回会让气泡上的勾反复变化。
     */
    applyReceipt(messageIds, level) {
      if (!Array.isArray(messageIds) || messageIds.length === 0) {
        return
      }
      Object.keys(this.messages).forEach((key) => {
        let changed = false
        this.messages[key].forEach((item) => {
          if (!item.self || item.recalled) {
            return
          }
          if (!messageIds.some((id) => sameId(id, item.messageId))) {
            return
          }
          if (Number(item.status) < level) {
            item.status = level
            item.statusDesc = STATUS_TEXT[level]
            changed = true
          }
        })
        if (changed) {
          persist(key, this.messages[key])
        }
      })
    },

    /**
     * 撤回消息（HTTP 通道）。
     *
     * 用 HTTP 而不是 WS 的 recall 上行：撤回有时限与权限限制，失败原因需要明确回给调用方，
     * 而 WS 只能靠 error 帧加 clientMsgId 反查，对不上时无法归因。
     * 撤回成功后本端也会收到 recall-notify（推送不排除操作者），applyRecall 是幂等的。
     */
    async recall(conversationId, messageId) {
      await messageApi.recallMessage(messageId)
      this.applyRecall({ conversationId, messageId, summary: '' })
    },

    /** 单端删除，只对自己不可见 */
    async remove(conversationId, messageId) {
      await messageApi.deleteMessage(messageId)
      const key = this.bucket(conversationId)
      this.messages[key] = this.messages[key].filter((item) => !sameId(item.messageId, messageId))
      persist(key, this.messages[key])
    },

    /**
     * 上报已读回执。
     * 连接可用时走 WS，省掉一次完整的 HTTP 往返；不可用时回退到等价接口。
     */
    reportRead(conversationId, maxSeq) {
      if (wsOpen() && wsSend('read', { conversationId, maxSeq })) {
        return Promise.resolve()
      }
      return messageApi.reportRead(conversationId, maxSeq)
    },

    /** 上报送达。空列表两条通道都会静默忽略 */
    reportDelivered(messageIds) {
      if (!messageIds || messageIds.length === 0) {
        return Promise.resolve()
      }
      if (wsOpen() && wsSend('delivered', { messageIds })) {
        return Promise.resolve()
      }
      return messageApi.reportDelivered(messageIds)
    },

    /**
     * 拉取离线消息。
     *
     * 登录与重连成功后各调一次。后端在返回的同时就推进了确认位点，
     * 但不清未读数 —— 未读要等用户真正打开会话才消失。
     */
    async pullOffline() {
      const list = (await messageApi.fetchOfflineMessages()) || []
      list.forEach((item) => this.appendMessage(item.conversationId, item, false))
      this.offlinePulled = true
      return list
    },

    /** 切换账号或退出时清空，避免把上一个人的聊天记录留给下一个登录者 */
    reset() {
      this.messages = {}
      this.hasMore = {}
      this.loadingHistory = {}
      this.offlinePulled = false
    }
  }
})

function findMessage(list, message) {
  const serverId = asId(message.messageId)
  const clientId = asId(message.clientMsgId)
  return (
    list.find((item) => {
      if (serverId && sameId(item.messageId, serverId)) {
        return true
      }
      // 只在对方也还没有 messageId 时用 clientMsgId 匹配，
      // 否则一条已落库的消息会被后到的本地占位覆盖回「发送中」
      return !item.messageId && !!clientId && sameId(item.clientMsgId, clientId)
    }) || null
  )
}

/** 服务端 messageId 优先，其次 clientMsgId，两者都没有的本地占位保留 */
function dedupe(list) {
  const seenServer = new Set()
  const seenClient = new Set()
  const result = []
  list.forEach((item) => {
    if (!item) {
      return
    }
    const serverId = asId(item.messageId)
    const clientId = asId(item.clientMsgId)
    if (serverId) {
      if (seenServer.has(serverId)) {
        return
      }
      seenServer.add(serverId)
    } else if (clientId) {
      if (seenClient.has(clientId)) {
        return
      }
      seenClient.add(clientId)
    }
    result.push(item)
  })
  return result
}

function persist(key, list) {
  if (!list || list.length === 0) {
    return
  }
  try {
    // 只缓存最近的若干条：全量写会很快撞上 localStorage 的配额，
    // 而用户回看很久以前的消息本来就要走历史接口
    const tail = list.slice(-CACHE_LIMIT)
    localStorage.setItem(CACHE_PREFIX + key, JSON.stringify(tail))
  } catch {
    // 配额满或隐私模式禁用了存储，缓存只是优化项，写不进去不影响功能
  }
}
