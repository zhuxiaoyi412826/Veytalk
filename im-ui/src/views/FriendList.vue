<template>
  <div class="friend-list">
    <aside class="friend-list__aside">
      <header class="friend-list__header">
        <el-tabs v-model="activeTab" class="friend-list__tabs">
          <el-tab-pane name="friends">
            <template #label>
              <span class="friend-list__tab-label">
                好友
                <span class="friend-list__tab-count">{{ friend.friends.length }}</span>
              </span>
            </template>
          </el-tab-pane>
          <el-tab-pane name="groups">
            <template #label>
              <span class="friend-list__tab-label">
                群聊
                <span class="friend-list__tab-count">{{ group.myGroups.length }}</span>
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>
        <div class="friend-list__header-actions">
          <template v-if="activeTab === 'friends'">
            <el-button text :icon="CircleClose" @click="openBlacklist">黑名单</el-button>
            <el-badge :value="friend.pendingCount" :max="99" :hidden="!friend.pendingCount">
              <el-button text :icon="Bell" @click="router.push({ name: 'friend-requests' })">申请</el-button>
            </el-badge>
          </template>
          <template v-else>
            <el-button text :icon="Plus" @click="showCreateGroup = true">创建</el-button>
          </template>
        </div>
      </header>

      <!-- 好友搜索 -->
      <div v-if="activeTab === 'friends'" class="friend-list__search">
        <el-input
          v-model.trim="keyword"
          placeholder="搜索备注、昵称或账号"
          clearable
          :prefix-icon="Search"
          @input="onSearchInput"
          @clear="search"
        />
      </div>

      <!-- 群聊搜索 -->
      <div v-if="activeTab === 'groups'" class="friend-list__search">
        <el-input
          v-model.trim="groupKeyword"
          placeholder="搜索群名称"
          clearable
          :prefix-icon="Search"
        />
      </div>

      <!-- 好友列表 -->
      <div v-show="activeTab === 'friends'" v-loading="friend.loading" class="friend-list__body im-scroll">
        <template v-for="group in friend.groupedFriends" :key="group.name">
          <div class="friend-list__group">
            <span>{{ group.name }}</span>
            <span class="friend-list__group-count">{{ group.friends.length }}</span>
          </div>

          <div
            v-for="item in group.friends"
            :key="item.friendId"
            class="friend"
            :class="{ 'friend--blocked': item.status === 2 }"
            title="点击发起聊天，右键更多操作"
            @click="startChat(item)"
            @contextmenu.prevent="openMenu($event, item)"
          >
            <UserAvatar :src="item.avatar" :name="item.displayName || item.nickname" :size="40" :online="!!item.online" />

            <div class="friend__body">
              <div class="friend__row">
                <span class="friend__name im-ellipsis">{{ item.displayName || item.nickname }}</span>
                <el-tag v-if="item.status === 2" size="small" type="info" effect="plain">已拉黑</el-tag>
              </div>
              <div class="friend__sub im-ellipsis">
                <!-- 设了备注就把真实昵称露出来，否则认不出这个人是谁 -->
                <template v-if="item.remark">@{{ item.username }}</template>
                <template v-else>{{ item.signature || `@${item.username}` }}</template>
              </div>
            </div>

            <div class="friend__actions" @click.stop>
              <el-tooltip content="查看资料" placement="top">
                <el-button text :icon="User" @click="openProfile(item)" />
              </el-tooltip>
              <el-tooltip content="发消息" placement="top">
                <el-button text type="primary" :icon="ChatDotRound" @click="startChat(item)" />
              </el-tooltip>
            </div>
          </div>
        </template>

        <el-empty
          v-if="!friend.loading && friend.friends.length === 0"
          :description="keyword ? `没有匹配「${keyword}」的好友` : '还没有好友，去「申请」页添加一个'"
          :image-size="80"
        />
      </div>

      <!-- 群聊列表 -->
      <div v-show="activeTab === 'groups'" v-loading="group.loading" class="friend-list__body im-scroll">
        <div
          v-for="item in filteredGroups"
          :key="item.groupId"
          class="friend"
          title="点击进入群聊，右键更多操作"
          @click="openGroupChat(item)"
          @contextmenu.prevent="openGroupMenu($event, item)"
        >
          <UserAvatar :src="item.avatar" :name="item.name" :size="40" />
          <div class="friend__body">
            <div class="friend__row">
              <span class="friend__name im-ellipsis">{{ item.name }}</span>
              <el-tag v-if="item.myRole === 1" size="small" type="warning" effect="plain">群主</el-tag>
              <el-tag v-else-if="item.myRole === 2" size="small" type="primary" effect="plain">管理员</el-tag>
            </div>
            <div class="friend__sub im-ellipsis">
              {{ item.memberCount }} 人
              <template v-if="item.myNickname"> · {{ item.myNickname }}</template>
            </div>
          </div>
        </div>

        <el-empty
          v-if="!group.loading && group.myGroups.length === 0"
          description="还没有群聊，点击右上角「创建」建一个"
          :image-size="80"
        />
      </div>
    </aside>

    <section class="friend-list__main">
      <el-empty description="从左侧选择一个好友发起聊天" :image-size="120" />
    </section>

    <ContextMenu
      v-model:visible="menu.visible"
      :x="menu.x"
      :y="menu.y"
      :items="menuItems"
      @select="onMenuSelect"
    />

    <ContextMenu
      v-model:visible="groupMenu.visible"
      :x="groupMenu.x"
      :y="groupMenu.y"
      :items="groupMenuItems"
      @select="onGroupMenuSelect"
    />

    <CreateGroupDialog v-model="showCreateGroup" />

    <!--
      黑名单集中管理：名单存在服务端（im_friend.status=2），多端看到的是同一份。
      单向阻断下拉黑只拦对方发来的消息，自己仍可发消息（发送即自动解除），
      所以这里既是「回看并移出误拉黑的人」的地方，也是把别人补进来的地方。
    -->
    <el-dialog v-model="blacklist.visible" title="黑名单" width="420px">
      <div class="blacklist__bar">
        <span class="blacklist__total">已拉黑 {{ blacklist.items.length }} 人</span>
        <el-button type="primary" plain size="small" :icon="Plus" @click="openBlacklistAdd">添加黑名单</el-button>
      </div>
      <div v-loading="blacklist.loading" class="blacklist__body im-scroll">
        <div v-for="item in blacklist.items" :key="item.friendId" class="blacklist__item">
          <UserAvatar :src="item.avatar" :name="item.displayName || item.nickname" :size="36" />
          <div class="blacklist__info">
            <div class="blacklist__name im-ellipsis">{{ item.displayName || item.nickname }}</div>
            <div class="blacklist__sub im-ellipsis">@{{ item.username }}</div>
          </div>
          <el-button text @click="openProfile(item)">资料</el-button>
          <el-button type="primary" text :loading="blacklist.acting === item.friendId" @click="unblockFromBlacklist(item)">
            移除黑名单
          </el-button>
        </div>
        <el-empty
          v-if="!blacklist.loading && blacklist.items.length === 0"
          description="还没有拉黑任何人，点上面的「添加黑名单」从好友里选"
          :image-size="60"
        />
      </div>
    </el-dialog>

    <!--
      添加黑名单：候选人只能从好友里选（后端 block 走 requireRelation，不是好友拉不了），
      已经拉黑的不再列出。多选一次批量提交，不必为一个人开一次确认框。
    -->
    <el-dialog v-model="blacklistAdd.visible" title="添加黑名单" width="420px" append-to-body>
      <div class="blacklist__tip">
        拉黑后对方发来的消息会被拦截，好友关系保留；你仍可主动发消息，发送后自动解除拉黑。
      </div>
      <el-input
        v-model.trim="blacklistAdd.keyword"
        placeholder="搜索备注、昵称或账号"
        size="small"
        clearable
        :prefix-icon="Search"
      />
      <div v-loading="blacklistAdd.loading" class="blacklist__pick im-scroll">
        <!-- 内容放在 el-checkbox 的默认槽里：整行就是一个 label，点哪里都只切一次 -->
        <el-checkbox
          v-for="item in blacklistPickCandidates"
          :key="item.friendId"
          class="blacklist__pick-item"
          :model-value="blacklistAdd.selected.has(item.friendId)"
          @change="(checked) => toggleBlacklistPick(item.friendId, checked)"
        >
          <UserAvatar :src="item.avatar" :name="item.displayName || item.nickname" :size="28" />
          <span class="blacklist__pick-name im-ellipsis">{{ item.displayName || item.nickname }}</span>
          <span class="blacklist__pick-sub im-ellipsis">@{{ item.username }}</span>
        </el-checkbox>
        <el-empty
          v-if="!blacklistAdd.loading && blacklistPickCandidates.length === 0"
          description="没有可拉黑的好友"
          :image-size="60"
        />
      </div>
      <template #footer>
        <div class="blacklist__footer">
          <span class="blacklist__pick-count">已选 {{ blacklistAdd.selected.size }} 人</span>
          <el-button @click="blacklistAdd.visible = false">取消</el-button>
          <el-button
            type="primary"
            :disabled="!blacklistAdd.selected.size"
            :loading="blacklistAdd.acting"
            @click="submitBlacklistAdd"
          >
            加入黑名单
          </el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="remarkDialog.visible" title="修改备注" width="360px" @opened="focusRemark">
      <el-input
        ref="remarkInputRef"
        v-model.trim="remarkDialog.value"
        placeholder="留空则显示对方昵称"
        maxlength="32"
        show-word-limit
        clearable
        @keyup.enter="submitRemark"
      />
      <template #footer>
        <el-button @click="remarkDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="remarkDialog.saving" @click="submitRemark">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, ChatDotRound, CircleClose, Plus, Search, User } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ContextMenu from '@/components/ContextMenu.vue'
import CreateGroupDialog from '@/components/CreateGroupDialog.vue'
import { useFriendStore, DEFAULT_GROUP } from '@/stores/friend'
import { fetchBlacklist, fetchFriends } from '@/api/friend'
import { useConversationStore } from '@/stores/conversation'
import { useGroupStore } from '@/stores/group'
import { asId } from '@/utils/id'

defineOptions({ name: 'FriendList' })

const router = useRouter()
const friend = useFriendStore()
const conversation = useConversationStore()
const group = useGroupStore()

const keyword = ref(friend.keyword)
const activeTab = ref('friends')
const groupKeyword = ref('')
const showCreateGroup = ref(false)

/** 按关键字过滤群列表 */
const filteredGroups = computed(() => {
  const kw = groupKeyword.value.toLowerCase()
  if (!kw) return group.myGroups
  return group.myGroups.filter((item) => (item.name || '').toLowerCase().includes(kw))
})

/* -------------------------------- 搜索 -------------------------------- */

let searchTimer = null

/**
 * 搜索走后端接口而不是在前端过滤本地数组。
 *
 * 后端的 keyword 会同时匹配备注、昵称与账号，而本地只有已经拉回来的这一页；
 * 前端过滤会漏掉「备注匹配但昵称不匹配」之外的大小写与模糊规则差异。
 * 300ms 去抖，避免每敲一个字发一次请求。
 */
function onSearchInput() {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(search, 300)
}

async function search() {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = null
  }
  try {
    await friend.fetchFriends(keyword.value)
  } catch {
    // 提示已由 request.js 弹出
  }
}

/* ------------------------------ 发起聊天 ------------------------------ */

/**
 * 发起聊天。
 *
 * /api/conversation/single 是幂等的：已有会话返回原 ID，没有就新建。
 * 所以这里不必先在列表里找一遍，也不担心重复点会造出多个会话。
 */
async function startChat(item) {
  try {
    const conversationId = await conversation.openWith(item.friendId)
    router.push({ name: 'chat', params: { conversationId: asId(conversationId) } })
  } catch {
    // 拉黑、对方注销等情况后端会拒绝，提示已弹出
  }
}

function openProfile(item) {
  router.push({ name: 'user-profile', params: { id: asId(item.friendId) } })
}

/* ------------------------------ 右键菜单 ------------------------------ */

const menu = reactive({ visible: false, x: 0, y: 0, target: null })

function openMenu(event, item) {
  menu.target = item
  menu.x = event.clientX
  menu.y = event.clientY
  menu.visible = true
}

const menuItems = computed(() => {
  const target = menu.target
  if (!target) {
    return []
  }
  const blocked = target.status === 2
  return [
    { key: 'chat', label: '发消息' },
    { key: 'profile', label: '查看资料' },
    { key: 'remark', label: '修改备注' },
    { key: 'group', label: '修改分组' },
    { key: 'block', label: blocked ? '移出黑名单' : '加入黑名单', danger: !blocked },
    { key: 'delete', label: '删除好友', danger: true }
  ]
})

async function onMenuSelect(action) {
  const target = menu.target
  if (!target) {
    return
  }
  try {
    switch (action) {
      case 'chat':
        await startChat(target)
        break
      case 'profile':
        openProfile(target)
        break
      case 'remark':
        openRemarkDialog(target)
        break
      case 'group':
        await promptGroup(target)
        break
      case 'block':
        await toggleBlock(target)
        break
      case 'delete':
        await confirmDelete(target)
        break
      default:
        break
    }
  } catch {
    // 提示已由下层弹出
  }
}

/* ------------------------------ 修改备注 ------------------------------ */

const remarkInputRef = ref(null)
const remarkDialog = reactive({ visible: false, saving: false, value: '', target: null })

function openRemarkDialog(target) {
  remarkDialog.target = target
  remarkDialog.value = target.remark || ''
  remarkDialog.visible = true
}

function focusRemark() {
  nextTick(() => remarkInputRef.value?.focus())
}

async function submitRemark() {
  const target = remarkDialog.target
  if (!target) {
    return
  }
  remarkDialog.saving = true
  try {
    await friend.updateRemark(target.friendId, remarkDialog.value)
    remarkDialog.visible = false
    ElMessage.success('备注已更新')
  } catch {
    // 提示已弹出，保留对话框让用户改完再试
  } finally {
    remarkDialog.saving = false
  }
}

/* ------------------------------ 修改分组 ------------------------------ */

/**
 * 修改分组。
 *
 * 用 ElMessageBox 的可输入模式而不是自造对话框：分组只有一个字符串，
 * 额外做一个「已有分组下拉 + 允许新建」的表单，收益抵不上它的复杂度。
 * 已有分组作为提示文案列出来，用户照着填即可。
 */
async function promptGroup(target) {
  const existing = friend.groups.filter((name) => name && name !== DEFAULT_GROUP)
  let value
  try {
    const result = await ElMessageBox.prompt(
      existing.length ? `已有分组：${existing.join('、')}` : '还没有其他分组，输入一个新名称即可创建',
      '修改分组',
      {
        inputValue: target.groupName || '',
        inputPlaceholder: `留空归入「${DEFAULT_GROUP}」`,
        inputValidator: (text) => (text || '').length <= 32 || '分组名最多 32 个字符',
        confirmButtonText: '保存',
        cancelButtonText: '取消'
      }
    )
    value = (result.value || '').trim()
  } catch {
    return
  }
  await friend.updateGroup(target.friendId, value)
  ElMessage.success('分组已更新')
}

/* ------------------------------ 拉黑 / 删除 ------------------------------ */

async function toggleBlock(target) {
  const blocked = target.status === 2
  if (!blocked) {
    try {
      await ElMessageBox.confirm(
        `拉黑后「${target.displayName || target.nickname}」发来的消息将被拦截；你仍可主动发消息，发送后自动解除拉黑。你们的好友关系保留。`,
        '加入黑名单',
        { confirmButtonText: '拉黑', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
    await friend.block(target.friendId)
    ElMessage.success('已加入黑名单')
    return
  }
  await friend.unblock(target.friendId)
  ElMessage.success('已移出黑名单')
}

/* ------------------------------ 黑名单管理 ------------------------------ */

const blacklist = reactive({ visible: false, loading: false, acting: 0, items: [] })

/** 拉黑 / 解除拉黑后都重拉一次名单，不本地拼数组：服务端才是名单的权威 */
async function reloadBlacklist() {
  blacklist.loading = true
  try {
    blacklist.items = (await fetchBlacklist()) || []
  } catch {
    // 提示已弹出，保留空列表比关掉对话框更能让用户意识到没加载成功
    blacklist.items = []
  } finally {
    blacklist.loading = false
  }
}

async function openBlacklist() {
  blacklist.visible = true
  await reloadBlacklist()
}

async function unblockFromBlacklist(item) {
  blacklist.acting = item.friendId
  try {
    await friend.unblock(item.friendId)
    blacklist.items = blacklist.items.filter((row) => row.friendId !== item.friendId)
    ElMessage.success('已移出黑名单')
    // store 里的行不在当前列表（被搜索关键字过滤掉了）时 block/unblock 的补丁打不上，
    // 跟着当前关键字重拉一次保证左侧好友行的「已拉黑」标记同步；
    // 重拉失败不影响已经生效的解除操作，静默吞掉，别让用户以为没拉黑成功
    await friend.fetchFriends(keyword.value).catch(() => {})
  } catch {
    // 提示已弹出
  } finally {
    blacklist.acting = 0
  }
}

/* ---------------------------- 添加黑名单 ---------------------------- */

const blacklistAdd = reactive({
  visible: false,
  loading: false,
  acting: false,
  keyword: '',
  /** 候选人（已从好友列表里刷掉拉黑态的） */
  rows: [],
  /** 已选 friendId；reactive 会把 Set 包成响应式代理，.size / .has() 都能被跟踪 */
  selected: new Set()
})

/** 候选人按关键字本地过滤：数据一次就拉全了，没必要每敲一个字回服务端 */
const blacklistPickCandidates = computed(() => {
  const kw = blacklistAdd.keyword.toLowerCase()
  if (!kw) {
    return blacklistAdd.rows
  }
  return blacklistAdd.rows.filter((item) => {
    const name = (item.displayName || item.nickname || '').toLowerCase()
    const account = (item.username || '').toLowerCase()
    return name.includes(kw) || account.includes(kw)
  })
})

function toggleBlacklistPick(friendId, checked) {
  if (checked) {
    blacklistAdd.selected.add(friendId)
  } else {
    blacklistAdd.selected.delete(friendId)
  }
}

async function openBlacklistAdd() {
  blacklistAdd.keyword = ''
  blacklistAdd.selected.clear()
  blacklistAdd.rows = []
  blacklistAdd.visible = true
  blacklistAdd.loading = true
  try {
    // 直接走接口而不是 friend.friends：store 里那份可能正被搜索关键字过滤着，
    // 拿它当候选人会少一批人；而不带关键字调 store 又会把用户当前的搜索结果洗掉
    const rows = (await fetchFriends()) || []
    const blockedIds = new Set(blacklist.items.map((item) => item.friendId))
    blacklistAdd.rows = rows.filter((item) => item.status !== 2 && !blockedIds.has(item.friendId))
  } catch {
    // 提示已弹出，空列表配 el-empty 比直接关掉对话框更好解释
    blacklistAdd.rows = []
  } finally {
    blacklistAdd.loading = false
  }
}

async function submitBlacklistAdd() {
  const ids = Array.from(blacklistAdd.selected)
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将把选中的 ${ids.length} 人加入黑名单，他们发来的消息会被拦截（好友关系保留，你主动发消息时自动解除拉黑）。`,
      '加入黑名单',
      { confirmButtonText: '加入黑名单', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  blacklistAdd.acting = true
  // 后端没有批量拉黑接口，而 block 是单行状态更新，串行最直接；
  // 一个人失败（关系已解除等）不中断整批，最后统一给条汇总提示
  let failed = 0
  for (const id of ids) {
    try {
      await friend.block(id)
    } catch {
      failed += 1
    }
  }
  blacklistAdd.acting = false
  blacklistAdd.selected.clear()
  blacklistAdd.visible = false
  const done = ids.length - failed
  // 两个刷新都不能让异常外抛：这里不是 async 事件处理里的 try，冒上去就是一堆 unhandled rejection
  await Promise.all([
    reloadBlacklist(),
    friend.fetchFriends(keyword.value).catch(() => {})
  ])
  if (failed && done) {
    ElMessage.warning(`已加入 ${done} 人，另有 ${failed} 人失败`)
  } else if (failed) {
    ElMessage.error('加入黑名单失败')
  } else {
    ElMessage.success(`已把 ${done} 人加入黑名单`)
  }
}

/**
 * 删除好友。
 *
 * 后端的删除是双向解除关系，聊天记录按会话隐藏处理，重新加为好友后历史仍在。
 * 提示语要说清「双向」，否则用户以为只是自己单方面移除。
 */
async function confirmDelete(target) {
  try {
    await ElMessageBox.confirm(
      `将解除与「${target.displayName || target.nickname}」的好友关系，对方也会从好友列表中失去你。`,
      '删除好友',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await friend.remove(target.friendId)
  ElMessage.success('已删除好友')
}

/* ------------------------------ 生命周期 ------------------------------ */

/* ------------------------------ 群聊操作 ------------------------------ */

const groupMenu = reactive({ visible: false, x: 0, y: 0, target: null })

function openGroupMenu(event, item) {
  groupMenu.target = item
  groupMenu.x = event.clientX
  groupMenu.y = event.clientY
  groupMenu.visible = true
}

const groupMenuItems = computed(() => {
  const target = groupMenu.target
  if (!target) return []
  return [
    { key: 'chat', label: '进入群聊' },
    { key: 'mute', label: target.muted ? '取消免打扰' : '消息免打扰' },
    { key: 'quit', label: '退出群聊', danger: true }
  ]
})

async function openGroupChat(item) {
  if (!item.conversationId) {
    ElMessage.warning('群会话尚未建立，请稍后再试')
    return
  }
  router.push({ name: 'chat', params: { conversationId: asId(item.conversationId) } })
}

async function onGroupMenuSelect(key) {
  const target = groupMenu.target
  if (!target) return
  try {
    switch (key) {
      case 'chat':
        await openGroupChat(target)
        break
      case 'mute':
        await conversation.toggleMute(target.conversationId, !target.muted)
        target.muted = !target.muted
        break
      case 'quit':
        await ElMessageBox.confirm(
          `退出「${target.name}」后需要重新邀请才能再次加入。`,
          '退出群聊',
          { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' }
        )
        await group.quitGroup(target.groupId)
        // 同时隐藏会话
        if (target.conversationId) {
          await conversation.remove(target.conversationId)
        }
        ElMessage.success('已退出群聊')
        break
      default:
        break
    }
  } catch {
    // 提示已弹出
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  // MainLayout 已经拉过一次好友列表；这里只在数据确实为空时补一次
  if (friend.friends.length === 0 && !friend.loading) {
    try {
      await friend.fetchFriends(keyword.value)
    } catch {
      // 提示已弹出
    }
  }
  // 拉取群列表
  if (group.myGroups.length === 0 && !group.loading) {
    group.fetchMyGroups()
  }
})
</script>

<style scoped>
.friend-list {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.friend-list__aside {
  display: flex;
  flex-direction: column;
  width: var(--im-list-width);
  flex: none;
  background: #f7f7f7;
  border-right: 1px solid var(--im-border);
}

.friend-list__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  flex: none;
  padding: 0 8px 0 12px;
}

.friend-list__header-actions {
  flex: none;
}

.friend-list__tabs {
  flex: 1;
  min-width: 0;
}

/* el-tabs 默认下边距太大，收紧 */
.friend-list__tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.friend-list__tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.friend-list__tab-label {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
}

.friend-list__tab-count {
  font-size: 11px;
  color: var(--im-text-secondary);
}

.friend-list__search {
  flex: none;
  padding: 0 12px 8px;
}

.friend-list__body {
  flex: 1;
  overflow-y: auto;
}

.friend-list__group {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  font-size: 12px;
  color: var(--im-text-secondary);
  background: #f0f0f0;
  /* 分组名在长列表里滚着滚着就不知道自己在哪一组了，吸顶解决 */
  position: sticky;
  top: 0;
  z-index: 1;
}

.friend-list__group-count {
  font-size: 11px;
}

.friend {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  cursor: pointer;
}

.friend:hover {
  background: #ebebeb;
}

.friend--blocked {
  opacity: 0.62;
}

.friend__body {
  flex: 1;
  min-width: 0;
}

.friend__row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.friend__name {
  font-size: 14px;
}

.friend__sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* 操作按钮只在悬停时出现，常驻会把 300px 的窄栏挤得名字都显示不全 */
.friend__actions {
  display: flex;
  align-items: center;
  flex: none;
  opacity: 0;
  transition: opacity 0.15s;
}

.friend:hover .friend__actions {
  opacity: 1;
}

.friend-list__main {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  min-width: 0;
  background: var(--im-chat-bg);
}

/* ------------------------------ 黑名单弹窗 ------------------------------ */
.blacklist__body {
  max-height: 320px;
  overflow-y: auto;
}

.blacklist__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.blacklist__info {
  flex: 1;
  min-width: 0;
}

.blacklist__name {
  font-size: 14px;
}

.blacklist__sub {
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* 工具栏：左边人数、右边「添加黑名单」，放在名单上方而不是对话框标题里，
   避开 el-dialog 的头部结构，也不用给关闭按钮让位 */
.blacklist__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.blacklist__total {
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* ---------------------------- 添加黑名单弹窗 ---------------------------- */
.blacklist__tip {
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--im-text-secondary, #909399);
}

.blacklist__pick {
  max-height: 260px;
  margin-top: 8px;
  overflow-y: auto;
}

/* 整行一个 el-checkbox（渲染出来就是一个 label），点名字与点勾效果一致。
   Element Plus 默认给 checkbox 固定高度与右外边距，这里改成整行块级才能把头像排开 */
.blacklist__pick-item {
  display: flex;
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 6px 4px;
  overflow: hidden;
}

.blacklist__pick-item:hover {
  background: #f5f7fa;
}

.blacklist__pick-item :deep(.el-checkbox__label) {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 400;
}

.blacklist__pick-name {
  min-width: 0;
  flex: none;
  max-width: 45%;
}

.blacklist__pick-sub {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* footer 默认右对齐，用 flex + margin-right:auto 把「已选 N 人」顶到最左 */
.blacklist__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.blacklist__pick-count {
  margin-right: auto;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/* ------------------------------ 窄屏 ------------------------------ */
@media (max-width: 768px) {
  .friend-list__aside {
    width: 100%;
    flex: 1;
    border-right: none;
  }

  /* 右侧占位区在窄屏没有意义：点好友会直接跳去聊天窗口 */
  .friend-list__main {
    display: none;
  }

  /* 触摸设备没有 hover，悬停才出现的操作按钮等于不存在，改成常显 */
  .friend__actions {
    opacity: 1;
  }
}
</style>
