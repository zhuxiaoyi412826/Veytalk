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
      所以这里的主要价值是「回看并移出误拉黑的人」，而不是找回被堵住的发送入口。
    -->
    <el-dialog v-model="blacklist.visible" title="黑名单" width="420px">
      <div v-loading="blacklist.loading" class="blacklist__body im-scroll">
        <div v-for="item in blacklist.items" :key="item.friendId" class="blacklist__item">
          <UserAvatar :src="item.avatar" :name="item.displayName || item.nickname" :size="36" />
          <div class="blacklist__info">
            <div class="blacklist__name im-ellipsis">{{ item.displayName || item.nickname }}</div>
            <div class="blacklist__sub im-ellipsis">@{{ item.username }}</div>
          </div>
          <el-button text @click="openProfile(item)">资料</el-button>
          <el-button type="primary" text :loading="blacklist.acting === item.friendId" @click="unblockFromBlacklist(item)">
            移出
          </el-button>
        </div>
        <el-empty v-if="!blacklist.loading && blacklist.items.length === 0" description="没有拉黑任何人" :image-size="60" />
      </div>
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
import { fetchBlacklist } from '@/api/friend'
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
    { key: 'block', label: blocked ? '取消拉黑' : '拉黑', danger: !blocked },
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
        '拉黑好友',
        { confirmButtonText: '拉黑', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
    await friend.block(target.friendId)
    ElMessage.success('已拉黑')
    return
  }
  await friend.unblock(target.friendId)
  ElMessage.success('已取消拉黑')
}

/* ------------------------------ 黑名单管理 ------------------------------ */

const blacklist = reactive({ visible: false, loading: false, acting: 0, items: [] })

async function openBlacklist() {
  blacklist.visible = true
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

async function unblockFromBlacklist(item) {
  blacklist.acting = item.friendId
  try {
    await friend.unblock(item.friendId)
    blacklist.items = blacklist.items.filter((row) => row.friendId !== item.friendId)
    ElMessage.success('已移出黑名单')
  } catch {
    // 提示已弹出
  } finally {
    blacklist.acting = 0
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
