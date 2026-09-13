import { defineStore } from 'pinia'
import * as conversationApi from '@/api/conversation'
import { asId, sameId } from '@/utils/id'
import { setUnreadCount } from '@/utils/title'

/**
 * 会话列表与未读数。
 *
 * 列表的初始顺序来自后端（置顶 &gt; 置顶时间 &gt; 最新消息时间），
 * 但新消息是通过 WebSocket 逐条到达的，不会重新拉整个列表，
 * 所以本地必须有一份和后端 ORDER BY 完全一致的排序规则，
 * 否则刷新前后列表顺序会不一样，用户会觉得会话「乱跳」。
 */

/** 与后端 selectViewsByUserId 的 ORDER BY 对齐：is_top DESC, top_time DESC, last_msg_time DESC, id DESC */
function sortConversations(list) {
  const timeOf = (value) => {
    if (!value) {
      return 0
    }
    const time = new Date(value).getTime()
    return Number.isNaN(time) ? 0 : time
  }
  return list.sort((a, b) => {
    if (!!a.top !== !!b.top) {
      return a.top ? -1 : 1
    }
    const topDiff = timeOf(b.topTime) - timeOf(a.topTime)
    if (topDiff !== 0) {
      return topDiff
    }
    const msgDiff = timeOf(b.lastMsgTime) - timeOf(a.lastMsgTime)
    if (msgDiff !== 0) {
      return msgDiff
    }
    // ID 可能是 19 位雪花字符串，不能直接相减，用字符串比较保证稳定即可
    return asId(b.conversationId).localeCompare(asId(a.conversationId))
  })
}

export const useConversationStore = defineStore('conversation', {
  state: () => ({
    list: [],
    loading: false,
    /** 全部会话未读之和，取自后端接口而非本地累加：多端已读只有服务端知道 */
    totalUnread: 0,
    /** 当前打开的会话 ID，统一存字符串形式 */
    activeId: ''
  }),

  getters: {
    activeConversation(state) {
      if (!state.activeId) {
        return null
      }
      return state.list.find((item) => asId(item.conversationId) === state.activeId) || null
    }
  },

  actions: {
    find(conversationId) {
      return this.list.find((item) => sameId(item.conversationId, conversationId)) || null
    },

    setActive(conversationId) {
      this.activeId = asId(conversationId)
    },

    async fetchList() {
      this.loading = true
      try {
        const list = await conversationApi.fetchConversations()
        this.list = sortConversations(list || [])
        // 列表本身就带每个会话的未读数，直接求和比再调一次 /unread/total 少一个往返，
        // 且两个来源不一致时以列表为准才不会让角标和列表内容对不上
        this.applyTotalUnread(this.list.reduce((sum, item) => sum + (item.unreadCount || 0), 0))
      } finally {
        this.loading = false
      }
      return this.list
    },

    async refreshTotalUnread() {
      try {
        this.applyTotalUnread(await conversationApi.fetchTotalUnread())
      } catch {
        // 角标刷新失败不值得打扰用户，下一次推送或列表刷新会纠正
      }
    },

    applyTotalUnread(count) {
      this.totalUnread = Math.max(0, Number(count) || 0)
      setUnreadCount(this.totalUnread)
    },

    /**
     * 打开与某人的单聊。
     *
     * 后端幂等：已有会话就返回原 ID，所以这里不需要先查列表。
     * 返回的会话可能还不在本地列表里（对方刚通过好友申请），补一次列表刷新。
     */
    async openWith(targetUserId) {
      const conversationId = await conversationApi.createSingleConversation(targetUserId)
      if (!this.find(conversationId)) {
        await this.fetchList()
      }
      return conversationId
    },

    /**
     * 新消息到达。
     *
     * 不需要判断「是不是自己发的」：后端的 message 推送是
     * pushToUsers(receivers, excludeUserId = fromUserId)，发送者本人永远收不到这一帧，
     * 自己的消息走的是 ack。所以这里递增未读总是对的。
     *
     * @param suppressUnread 消息正显示在当前打开的会话里时为 true，
     *                       此时不递增未读 —— 否则角标会先跳 +1 再被 markRead 清零，闪一下。
     */
    applyIncoming(dto, suppressUnread) {
      if (!dto) {
        return
      }
      const target = this.find(dto.conversationId)
      if (!target) {
        // 列表里没有这条会话：可能是对方刚刚通过好友申请新建的单聊。
        // 拉一次列表比在本地凭空拼一个缺少昵称头像的会话项可靠得多。
        this.fetchList()
        return
      }
      target.lastMsgId = dto.messageId
      target.lastMsgContent = summarize(dto)
      target.lastMsgType = dto.msgType
      target.lastMsgTime = dto.sendTime
      if (!suppressUnread) {
        target.unreadCount = (target.unreadCount || 0) + 1
      }
      sortConversations(this.list)
      this.applyTotalUnread(this.list.reduce((sum, item) => sum + (item.unreadCount || 0), 0))
    },

    /**
     * 发送回执。
     *
     * ack 推给发送者的全部设备，所以本地没有这个 clientMsgId 时
     * 说明消息来自自己的另一台设备，同样要当作新消息刷到列表上。
     */
    applyAck(dto) {
      if (!dto) {
        return
      }
      const target = this.find(dto.conversationId)
      if (!target) {
        return
      }
      target.lastMsgId = dto.messageId
      target.lastMsgContent = summarize(dto)
      target.lastMsgType = dto.msgType
      target.lastMsgTime = dto.sendTime
      sortConversations(this.list)
    },

    /**
     * 撤回通知。
     *
     * 只有被撤回的正好是最后一条消息时才改摘要，和后端的口径一致；
     * 撤回也不该把会话重新顶到列表最前，所以不动 lastMsgTime。
     */
    applyRecall(payload) {
      if (!payload) {
        return
      }
      const target = this.find(payload.conversationId)
      if (!target || !sameId(target.lastMsgId, payload.messageId)) {
        return
      }
      target.lastMsgContent = payload.summary || '对方撤回了一条消息'
    },

    /** 未读数变更推送：data = { conversationId, unreadCount, total } */
    applyUnread(payload) {
      if (!payload) {
        return
      }
      const target = this.find(payload.conversationId)
      if (target) {
        target.unreadCount = Number(payload.unreadCount) || 0
      }
      if (payload.total !== undefined && payload.total !== null) {
        this.applyTotalUnread(payload.total)
      } else {
        this.applyTotalUnread(this.list.reduce((sum, item) => sum + (item.unreadCount || 0), 0))
      }
    },

    /** 好友在线状态变更推送：data = { userId, online } */
    applyOnlineState(payload) {
      if (!payload) {
        return
      }
      this.list.forEach((item) => {
        // 单聊会话的 targetId 就是对方用户 ID
        if (item.type === 1 && sameId(item.targetId, payload.userId)) {
          item.online = !!payload.online
        }
      })
    },

    /**
     * 标记已读。
     *
     * 乐观更新：先把本地未读清零再发请求。等接口返回的话，用户在快速切换会话时
     * 会看到角标迟迟不掉，而失败的概率远低于这个体验损耗。
     */
    async markRead(conversationId, lastAckSeq) {
      const target = this.find(conversationId)
      if (!target || !(target.unreadCount > 0)) {
        // 没有未读就不必发请求：切换会话时会高频走到这里
        if (target) {
          target.unreadCount = 0
        }
        return
      }
      target.unreadCount = 0
      target.atFlag = false
      if (lastAckSeq !== undefined && lastAckSeq !== null) {
        target.lastAckSeq = lastAckSeq
      }
      this.applyTotalUnread(this.list.reduce((sum, item) => sum + (item.unreadCount || 0), 0))
      try {
        await conversationApi.markConversationRead(conversationId, lastAckSeq)
      } catch {
        // 失败不回滚未读数：下一次的 unread 推送或列表刷新会带回服务端的真实值
        this.refreshTotalUnread()
      }
    },

    async toggleTop(conversationId, enabled) {
      await conversationApi.setConversationTop(conversationId, enabled)
      const target = this.find(conversationId)
      if (target) {
        target.top = enabled
        target.topTime = enabled ? new Date().toISOString() : null
      }
      sortConversations(this.list)
    },

    async toggleMute(conversationId, enabled) {
      await conversationApi.setConversationMute(conversationId, enabled)
      const target = this.find(conversationId)
      if (target) {
        target.muted = enabled
      }
    },

    async remove(conversationId) {
      await conversationApi.removeConversation(conversationId)
      this.list = this.list.filter((item) => !sameId(item.conversationId, conversationId))
      if (sameId(this.activeId, conversationId)) {
        this.activeId = ''
      }
      this.applyTotalUnread(this.list.reduce((sum, item) => sum + (item.unreadCount || 0), 0))
    },

    /** 会话详情，用于直接进入 /chat/:id 而列表还没加载完的场景 */
    async ensureLoaded(conversationId) {
      const existing = this.find(conversationId)
      if (existing) {
        return existing
      }
      try {
        return await conversationApi.fetchConversation(conversationId)
      } catch {
        return null
      }
    },

    reset() {
      this.list = []
      this.totalUnread = 0
      this.activeId = ''
      setUnreadCount(0)
    }
  }
})

/**
 * 会话列表里的摘要文案。
 *
 * 后端存进 last_msg_content 的已经是可直接展示的摘要（附件类是「[图片]」这样的占位），
 * 但 WebSocket 推来的 MessageDTO 带的是消息原文 —— 附件消息的 content 是文件 ID，
 * 直接显示会在会话列表里露出一串雪花数字。所以这里按类型重新走一遍和后端口径相同的转换。
 */
function summarize(dto) {
  switch (Number(dto.msgType)) {
    case 2:
      return '[图片]'
    case 4:
      return '[语音]'
    case 3: {
      const fileName = dto.extra && dto.extra.fileName
      return fileName ? `[文件] ${fileName}` : '[文件]'
    }
    default:
      return dto.content || ''
  }
}
