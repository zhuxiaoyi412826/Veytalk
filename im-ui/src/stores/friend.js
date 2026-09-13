import { defineStore } from 'pinia'
import * as friendApi from '@/api/friend'
import { sameId } from '@/utils/id'

/**
 * 好友列表与好友申请。
 *
 * 列表按 groupName 分组展示，但分组数据本身不在 FriendVO 之外单独维护 ——
 * FriendVO.groupName 已经是权威值，这里只做一次派生分组，避免两份数据对不上。
 */

/** 未设置分组的好友归到这一组，排在最后 */
const DEFAULT_GROUP = '我的好友'

export const useFriendStore = defineStore('friend', {
  state: () => ({
    friends: [],
    groups: [],
    loading: false,
    keyword: '',
    /** 收到的申请，按状态过滤后的当前页 */
    received: [],
    receivedTotal: 0,
    /** 我发出的申请 */
    sent: [],
    sentTotal: 0,
    /** 待处理申请数，导航红点用 */
    pendingCount: 0
  }),

  getters: {
    /**
     * 分组后的好友：[{ name, friends }]，有分组的按名称排序，默认组垫底。
     * 用数组而不是对象，是为了让模板能直接 v-for 而不用再 Object.keys 一次。
     */
    groupedFriends(state) {
      const buckets = new Map()
      state.friends.forEach((friend) => {
        const name = friend.groupName || DEFAULT_GROUP
        if (!buckets.has(name)) {
          buckets.set(name, [])
        }
        buckets.get(name).push(friend)
      })
      return Array.from(buckets.entries())
        .map(([name, friends]) => ({ name, friends }))
        .sort((a, b) => {
          if (a.name === DEFAULT_GROUP) {
            return 1
          }
          if (b.name === DEFAULT_GROUP) {
            return -1
          }
          return a.name.localeCompare(b.name, 'zh-Hans-CN')
        })
    },

    friendOf: (state) => (userId) => state.friends.find((item) => sameId(item.friendId, userId)) || null
  },

  actions: {
    async fetchFriends(keyword) {
      this.loading = true
      try {
        this.keyword = keyword || ''
        this.friends = (await friendApi.fetchFriends(keyword)) || []
      } finally {
        this.loading = false
      }
      return this.friends
    },

    async fetchGroups() {
      this.groups = (await friendApi.fetchFriendGroups()) || []
      return this.groups
    },

    async fetchReceived(params = {}) {
      const page = await friendApi.fetchReceivedRequests(params)
      this.received = (page && page.records) || []
      this.receivedTotal = (page && page.total) || 0
      return this.received
    },

    async fetchSent(params = {}) {
      const page = await friendApi.fetchSentRequests(params)
      this.sent = (page && page.records) || []
      this.sentTotal = (page && page.total) || 0
      return this.sent
    },

    async refreshPendingCount() {
      try {
        this.pendingCount = Number(await friendApi.fetchPendingCount()) || 0
      } catch {
        // 红点拉取失败不影响主流程，静默保持上一次的值
      }
    },

    /** 发起申请：targetUserId 与 targetAccount 二选一 */
    async apply(payload) {
      const vo = await friendApi.applyFriend(payload)
      await this.refreshPendingCount()
      return vo
    },

    /** 同意申请，返回后端新建的单聊会话 ID */
    async accept(requestId) {
      const conversationId = await friendApi.acceptRequest(requestId)
      // 关系已经变了，列表、分组、待处理数都要重新取；
      // 逐个打补丁很容易漏掉某一处，而这些都是低频操作
      await Promise.all([this.fetchFriends(this.keyword), this.fetchGroups(), this.refreshPendingCount()])
      return conversationId
    },

    async reject(requestId) {
      await friendApi.rejectRequest(requestId)
      await this.refreshPendingCount()
    },

    async updateRemark(friendId, remark) {
      await friendApi.updateRemark(friendId, remark)
      const target = this.friendOf(friendId)
      if (target) {
        target.remark = remark
        // displayName 是后端算好的「备注优先，其次昵称」，本地要跟着改，
        // 否则列表上显示的还是旧名字，要等下一次刷新才对
        target.displayName = remark || target.nickname
      }
    },

    async updateGroup(friendId, groupName) {
      await friendApi.updateGroup(friendId, groupName)
      const target = this.friendOf(friendId)
      if (target) {
        target.groupName = groupName
      }
      await this.fetchGroups()
    },

    async remove(friendId) {
      await friendApi.removeFriend(friendId)
      this.friends = this.friends.filter((item) => !sameId(item.friendId, friendId))
    },

    async block(friendId) {
      await friendApi.blockFriend(friendId)
      const target = this.friendOf(friendId)
      if (target) {
        target.blocked = true
        target.status = 2
      }
    },

    async unblock(friendId) {
      await friendApi.unblockFriend(friendId)
      const target = this.friendOf(friendId)
      if (target) {
        target.blocked = false
        target.status = 1
      }
    },

    /** 好友在线状态变更推送：data = { userId, online } */
    applyOnlineState(payload) {
      if (!payload) {
        return
      }
      const target = this.friendOf(payload.userId)
      if (target) {
        target.online = !!payload.online
      }
    },

    /** 收到好友申请推送后刷新列表与红点 */
    async onFriendRequestNotify() {
      await this.refreshPendingCount()
    },

    /** 对方同意了我的申请：把他加进好友列表 */
    async onFriendAcceptedNotify() {
      await Promise.all([this.fetchFriends(this.keyword), this.fetchGroups()])
    },

    reset() {
      this.friends = []
      this.groups = []
      this.received = []
      this.receivedTotal = 0
      this.sent = []
      this.sentTotal = 0
      this.pendingCount = 0
      this.keyword = ''
    }
  }
})

export { DEFAULT_GROUP }
