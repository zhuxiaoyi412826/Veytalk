import { defineStore } from 'pinia'
import * as groupApi from '@/api/group'
import { sameId } from '@/utils/id'

/**
 * 群组状态管理。
 *
 * 群列表按入群时间倒序（后端已排好），成员列表分页加载。
 * 群详情（含 myRole / myMuted 等视角字段）不能跨用户缓存，
 * 但同一用户在同一次登录内可以放心使用。
 */

/** 群角色常量，与后端 GroupMember.role 对齐 */
export const ROLE_OWNER = 1
export const ROLE_ADMIN = 2
export const ROLE_MEMBER = 3

/** 角色文案 */
export function roleDesc(role) {
  switch (Number(role)) {
    case ROLE_OWNER: return '群主'
    case ROLE_ADMIN: return '管理员'
    default: return '成员'
  }
}

export const useGroupStore = defineStore('group', {
  state: () => ({
    /** 我的群聊列表 */
    myGroups: [],
    loading: false,
    /** 当前正在查看的群详情（GroupVO） */
    currentGroup: null,
    /** 当前群的成员列表 */
    members: [],
    membersTotal: 0,
    membersLoading: false
  }),

  getters: {
    /** 按群 ID 查找本地群信息 */
    groupOf: (state) => (groupId) =>
      state.myGroups.find((item) => sameId(item.groupId, groupId)) || null
  },

  actions: {
    async fetchMyGroups() {
      this.loading = true
      try {
        this.myGroups = (await groupApi.fetchMyGroups()) || []
      } finally {
        this.loading = false
      }
      return this.myGroups
    },

    async fetchDetail(groupId) {
      try {
        this.currentGroup = await groupApi.fetchGroupDetail(groupId)
      } catch {
        this.currentGroup = null
      }
      return this.currentGroup
    },

    async createGroup(data) {
      const vo = await groupApi.createGroup(data)
      // 创建成功后追加到列表头部（新群排在最前）
      if (vo) {
        this.myGroups.unshift(vo)
      }
      return vo
    },

    async updateGroup(groupId, data) {
      const vo = await groupApi.updateGroup(groupId, data)
      if (vo) {
        this.currentGroup = vo
        const idx = this.myGroups.findIndex((item) => sameId(item.groupId, groupId))
        if (idx >= 0) {
          this.myGroups[idx] = vo
        }
      }
      return vo
    },

    async fetchMembers(groupId, current = 1, size = 50) {
      this.membersLoading = true
      try {
        const page = await groupApi.fetchMembers(groupId, current, size)
        const records = (page && page.records) || []
        if (current === 1) {
          this.members = records
        } else {
          // 追加后续页，去重
          const existIds = new Set(this.members.map((m) => String(m.userId)))
          records.forEach((m) => {
            if (!existIds.has(String(m.userId))) {
              this.members.push(m)
            }
          })
        }
        this.membersTotal = (page && page.total) || 0
        return records
      } finally {
        this.membersLoading = false
      }
    },

    async addMembers(groupId, userIds) {
      const added = await groupApi.addMembers(groupId, userIds)
      // 成员变动后刷新成员列表
      await this.fetchMembers(groupId)
      // 更新群详情里的成员数
      if (this.currentGroup && sameId(this.currentGroup.groupId, groupId)) {
        this.currentGroup.memberCount = (this.currentGroup.memberCount || 0) + (added ? added.length : 0)
      }
      return added
    },

    async removeMember(groupId, userId) {
      await groupApi.removeMember(groupId, userId)
      this.members = this.members.filter((m) => !sameId(m.userId, userId))
      this.membersTotal = Math.max(0, this.membersTotal - 1)
      if (this.currentGroup && sameId(this.currentGroup.groupId, groupId)) {
        this.currentGroup.memberCount = Math.max(0, (this.currentGroup.memberCount || 0) - 1)
      }
    },

    async muteMember(groupId, userId, muted, minutes) {
      await groupApi.muteMember(groupId, userId, muted, minutes)
      const target = this.members.find((m) => sameId(m.userId, userId))
      if (target) {
        target.muted = muted
        target.muteEndTime = muted && minutes ? new Date(Date.now() + minutes * 60000).toISOString() : null
      }
    },

    async updateRole(groupId, userId, role) {
      await groupApi.updateRole(groupId, userId, role)
      const target = this.members.find((m) => sameId(m.userId, userId))
      if (target) {
        target.role = role
        target.roleDesc = roleDesc(role)
      }
    },

    async toggleMuteAll(groupId, muteAll) {
      await groupApi.toggleMuteAll(groupId, muteAll)
      if (this.currentGroup && sameId(this.currentGroup.groupId, groupId)) {
        this.currentGroup.muteAll = muteAll
      }
    },

    async transferGroup(groupId, userId) {
      await groupApi.transferGroup(groupId, userId)
      // 转让后刷新详情，角色已变
      await this.fetchDetail(groupId)
      await this.fetchMembers(groupId)
    },

    async updateMyNickname(groupId, nickname) {
      await groupApi.updateMyNickname(groupId, nickname)
      if (this.currentGroup && sameId(this.currentGroup.groupId, groupId)) {
        this.currentGroup.myNickname = nickname || null
      }
    },

    async quitGroup(groupId) {
      await groupApi.quitGroup(groupId)
      this.myGroups = this.myGroups.filter((item) => !sameId(item.groupId, groupId))
      if (this.currentGroup && sameId(this.currentGroup.groupId, groupId)) {
        this.currentGroup = null
      }
    },

    async dismissGroup(groupId) {
      await groupApi.dismissGroup(groupId)
      this.myGroups = this.myGroups.filter((item) => !sameId(item.groupId, groupId))
      if (this.currentGroup && sameId(this.currentGroup.groupId, groupId)) {
        this.currentGroup = null
      }
    },

    /** 收到群组相关 WS 通知时刷新群列表 */
    async onGroupNotify() {
      await this.fetchMyGroups()
    },

    reset() {
      this.myGroups = []
      this.loading = false
      this.currentGroup = null
      this.members = []
      this.membersTotal = 0
      this.membersLoading = false
    }
  }
})
