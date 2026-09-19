<template>
  <div class="chat-home" :class="{ 'chat-home--chatting': !!conversation.activeId }">
    <!-- ==================== 左侧：会话列表 ==================== -->
    <aside class="chat-home__aside">
      <header class="chat-home__header">
        <div class="chat-home__heading">
          <span class="chat-home__title">消息</span>
          <span v-if="conversation.totalUnread" class="chat-home__total">
            {{ conversation.totalUnread > 99 ? '99+' : conversation.totalUnread }} 条未读
          </span>
        </div>
        <el-tooltip content="刷新会话列表" placement="bottom">
          <el-button text :icon="Refresh" :loading="conversation.loading" @click="conversation.fetchList()" />
        </el-tooltip>
      </header>

      <!-- 全局消息搜索：跨全部会话检索，与聊天窗口内的「仅当前会话」搜索区分开 -->
      <div class="chat-home__search">
        <el-input
          v-model.trim="globalKeyword"
          placeholder="搜索全部会话的消息"
          clearable
          :prefix-icon="Search"
          @clear="closeGlobalSearch"
        />
      </div>

      <!-- 搜索结果面板：有关键字时替换会话列表展示，点击某条跳转到对应会话 -->
      <div v-if="globalSearching" v-loading="globalLoading" class="chat-home__search-results im-scroll">
        <div
          v-for="item in globalResults"
          :key="item.messageId"
          class="gsearch"
          @click="openGlobalResult(item)"
        >
          <div class="gsearch__head">
            <span class="gsearch__conv im-ellipsis">{{ convNameOf(item) }}</span>
            <span class="gsearch__time">{{ formatConvTime(item.sendTime) }}</span>
          </div>
          <div class="gsearch__body im-ellipsis">
            <span class="gsearch__sender">{{ item.fromNickname }}：</span>
            <span class="gsearch__content" v-html="highlightGlobal(item.content)"></span>
          </div>
        </div>
        <div v-if="globalHasMore" class="gsearch__more">
          <el-button link type="primary" :loading="globalLoading" @click="loadMoreGlobal">加载更多</el-button>
        </div>
        <div v-if="globalSearched && !globalResults.length && !globalLoading" class="gsearch__empty">
          未找到匹配的消息
        </div>
      </div>

      <div v-else v-loading="conversation.loading && conversation.list.length === 0" class="chat-home__items im-scroll">
        <div
          v-for="item in conversation.list"
          :key="item.conversationId"
          class="conv"
          :class="{ 'conv--active': isActive(item), 'conv--top': item.top }"
          @click="open(item)"
          @contextmenu.prevent="openMenu($event, item)"
        >
          <UserAvatar
            :src="item.avatar"
            :name="item.name"
            :size="settings.avatarPx"
            :online="item.type === 1 ? item.online : null"
          />

          <div class="conv__body">
            <div class="conv__row">
              <span class="conv__name im-ellipsis">{{ item.name }}</span>
              <span class="conv__time">{{ formatConvTime(item.lastMsgTime) }}</span>
            </div>
            <div class="conv__row">
              <span class="conv__summary im-ellipsis">
                <span v-if="item.atFlag" class="conv__at">[有人@我]</span>
                <span v-if="settings.showPreview">{{ item.lastMsgContent || '暂无消息' }}</span>
              </span>
              <span class="conv__flags">
                <el-icon v-if="item.muted" class="conv__flag" title="已开启消息免打扰"><MuteNotification /></el-icon>
                <el-badge
                  v-if="item.unreadCount"
                  :value="item.unreadCount"
                  :max="99"
                  :type="item.muted ? 'info' : 'danger'"
                />
              </span>
            </div>
          </div>
        </div>

        <el-empty
          v-if="!conversation.loading && conversation.list.length === 0"
          description="还没有会话，去好友列表发起聊天吧"
          :image-size="72"
        />
      </div>
    </aside>

    <!-- ==================== 右侧：聊天窗口 ==================== -->
    <section class="chat-home__main" :style="settings.chatBackgroundStyle">
      <ChatWindow v-if="conversation.activeId" :key="conversation.activeId" :conversation-id="conversation.activeId" />
      <div v-else class="chat-home__placeholder">
        <el-empty description="选择左侧的一个会话开始聊天" :image-size="120" />
      </div>
    </section>

    <ContextMenu
      v-model:visible="menu.visible"
      :x="menu.x"
      :y="menu.y"
      :items="menuItems"
      @select="onMenuSelect"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MuteNotification, Refresh, Search } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ContextMenu from '@/components/ContextMenu.vue'
import ChatWindow from './ChatWindow.vue'
import { useConversationStore } from '@/stores/conversation'
import { useSettingsStore } from '@/stores/settings'
import { useChatStore } from '@/stores/chat'
import { clearConversationMessages, searchMessages } from '@/api/message'
import { formatConvTime } from '@/utils/format'
import { asId, sameId } from '@/utils/id'

/**
 * 首页：左侧会话列表 + 右侧聊天窗口。
 *
 * 路由是 /chat/:conversationId?，选中与未选中会话共用这一个组件实例，
 * 切换会话时不重新挂载，列表的滚动位置才能保住。
 */
defineOptions({ name: 'ChatHome' })

const route = useRoute()
const router = useRouter()
const conversation = useConversationStore()
const settings = useSettingsStore()
const chat = useChatStore()

function isActive(item) {
  return sameId(item.conversationId, conversation.activeId)
}

/**
 * 打开会话。
 *
 * 走路由而不是直接 setActive：地址栏要能反映当前会话，
 * 这样刷新页面、复制链接给别人都能落在同一个聊天窗口上。
 */
function open(item) {
  const id = asId(item.conversationId)
  if (id === asId(route.params.conversationId)) {
    return
  }
  router.push({ name: 'chat', params: { conversationId: id } })
}

/**
 * 路由参数 → store 的当前会话。
 *
 * keep-alive 下离开 /chat 后这个 watcher 依然存活，而那时 route.params 里
 * 已经没有 conversationId 了，不加 route.name 判断会把选中项清空，
 * 表现为「从好友页回来，右侧聊天窗口变成空的」。
 */
watch(
  () => route.params.conversationId,
  (value) => {
    if (route.name !== 'chat') {
      return
    }
    conversation.setActive(value || '')
  },
  { immediate: true }
)

onMounted(() => {
  // MainLayout 在 beforeMount 阶段就已经发起列表请求，loading 为真说明数据在路上；
  // 这里只兜住「直接刷新到 /chat/xxx 且父组件那次请求已经失败」的情况
  if (conversation.list.length === 0 && !conversation.loading) {
    conversation.fetchList()
  }
})

/* ------------------------------ 全局消息搜索 ------------------------------ */

const globalKeyword = ref('')
const globalResults = ref([])
const globalLoading = ref(false)
const globalHasMore = ref(false)
/** 是否已完成过至少一次检索：用于区分「正在搜」与「真的没结果」，避免防抖期间闪一下空态 */
const globalSearched = ref(false)
/** 是否处于「展示搜索结果」态：有关键字即为真，用于替换会话列表 */
const globalSearching = computed(() => !!globalKeyword.value)
let globalTimer = null

/** 300ms 防抖，避免每敲一个字就打一次跨会话检索 */
watch(globalKeyword, (val) => {
  if (globalTimer) clearTimeout(globalTimer)
  if (!val) {
    globalResults.value = []
    globalHasMore.value = false
    globalSearched.value = false
    globalLoading.value = false
    return
  }
  // 关键字一变就进入 loading，防抖与请求期间面板显示转圈而不是闪一下空态
  globalLoading.value = true
  globalSearched.value = false
  globalTimer = setTimeout(doGlobalSearch, 300)
})

async function doGlobalSearch() {
  if (!globalKeyword.value) return
  globalLoading.value = true
  try {
    // 不传 conversationId：后端跨当前用户全部会话检索
    const page = await searchMessages({ keyword: globalKeyword.value, current: 1, size: 20 })
    globalResults.value = (page && page.records) || []
    globalHasMore.value = globalResults.value.length >= 20
  } catch {
    // request.js 已处理
  } finally {
    globalLoading.value = false
    globalSearched.value = true
  }
}

async function loadMoreGlobal() {
  if (!globalKeyword.value || globalLoading.value) return
  globalLoading.value = true
  try {
    const nextPage = Math.ceil(globalResults.value.length / 20) + 1
    const page = await searchMessages({ keyword: globalKeyword.value, current: nextPage, size: 20 })
    const records = (page && page.records) || []
    globalResults.value.push(...records)
    globalHasMore.value = records.length >= 20
  } catch {
    // request.js 已处理
  } finally {
    globalLoading.value = false
  }
}

function closeGlobalSearch() {
  if (globalTimer) clearTimeout(globalTimer)
  globalKeyword.value = ''
  globalResults.value = []
  globalHasMore.value = false
  globalSearched.value = false
  globalLoading.value = false
}

/** 结果所属会话的展示名：优先取本地会话列表，隐藏会话等取不到时兜底 */
function convNameOf(item) {
  return conversation.find(item.conversationId)?.name || '会话'
}

/** 点击结果跳转到对应会话的聊天页，并收起搜索面板 */
function openGlobalResult(item) {
  const id = asId(item.conversationId)
  closeGlobalSearch()
  router.push({ name: 'chat', params: { conversationId: id } })
}

/** 关键字高亮：转义 HTML 后把匹配片段包成 <mark> */
function highlightGlobal(text) {
  if (!text) return ''
  const escaped = text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
  const kw = globalKeyword.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(new RegExp(kw, 'gi'), (m) => `<mark>${m}</mark>`)
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
  return [
    { key: 'top', label: target.top ? '取消置顶' : '置顶会话' },
    { key: 'mute', label: target.muted ? '取消免打扰' : '消息免打扰' },
    // 没有未读时置灰而不是隐藏：菜单项数量突变会让人以为点错了行
    { key: 'read', label: '标记已读', disabled: !target.unreadCount },
    { key: 'remove', label: '删除会话', danger: true }
  ]
})

async function onMenuSelect(key) {
  const target = menu.target
  if (!target) {
    return
  }
  const id = target.conversationId
  try {
    switch (key) {
      case 'top':
        await conversation.toggleTop(id, !target.top)
        break
      case 'mute':
        await conversation.toggleMute(id, !target.muted)
        break
      case 'read':
        // 不传位点：后端在 lastAckSeq 为空时按会话当前最大 seq 全量已读
        await conversation.markRead(id)
        break
      case 'remove':
        await confirmRemove(target)
        break
      default:
        break
    }
  } catch {
    // request.js 已经弹过错误提示，这里只保证不让异常冒到全局
  }
}

/**
 * 删除会话，二选一：保留聊天记录 / 不保留聊天记录。
 *
 * 用 distinguishCancelAndClose 把三个出口区分开：
 * confirm = 不保留（先清空记录再移除）、cancel = 保留（仅移除）、close(X / ESC) = 放弃。
 */
async function confirmRemove(target) {
  let mode
  try {
    await ElMessageBox.confirm(
      `保留聊天记录：仅把「${target.name}」从列表移除，记录仍在，对方再发消息时会话会重新出现。\n`
        + '不保留聊天记录：同时清除你在本会话的全部聊天记录（仅对你生效，对方不受影响），且无法恢复。',
      '删除会话',
      {
        distinguishCancelAndClose: true,
        confirmButtonText: '不保留聊天记录',
        cancelButtonText: '保留聊天记录',
        confirmButtonClass: 'el-button--danger',
        type: 'warning'
      }
    )
    mode = 'clear'
  } catch (action) {
    // cancel = 点了「保留聊天记录」；close = 点 X / ESC 放弃
    if (action !== 'cancel') {
      return
    }
    mode = 'keep'
  }
  // 必须在 remove() 之前记下「删的是不是当前打开的会话」：remove() 内部会把 activeId 清空，
  // 之后再比对就永远是 false，router.replace 不会执行，路由停在 /chat/:id 不动。
  // 地址栏残留这个 id 时，对方再发消息让会话重新出现，点击它会命中 open() 里
  // 「id 与当前路由相同就不跳转」的短路，表现为「点了没反应」，只有先去好友页把路由参数
  // 冲掉再回来才能进 —— 这正是用户反馈的第二个 bug。
  const wasActive = sameId(conversation.activeId, target.conversationId)
  if (mode === 'clear') {
    await clearConversationMessages(target.conversationId)
    // 服务端清空只删了远端，本地内存列表与 localStorage 缓存还留着旧消息；不清的话
    // 会话重新出现、点进去 loadHistory(reset) 会把缓存合并回来，刚清掉的记录又复活
    // —— 这是用户反馈的第一个 bug。
    chat.clearConversation(target.conversationId)
  }
  await conversation.remove(target.conversationId)
  if (wasActive) {
    router.replace({ name: 'chat' })
  }
  ElMessage.success(mode === 'clear' ? '已删除会话并清除聊天记录' : '已从列表移除')
}
</script>

<style scoped>
.chat-home {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.chat-home__aside {
  display: flex;
  flex-direction: column;
  width: var(--im-list-width);
  flex: none;
  background: var(--im-list-bg, #f7f7f7);
  border-right: 1px solid var(--im-border);
}

.chat-home__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  flex: none;
  padding: 0 8px 0 16px;
}

.chat-home__heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.chat-home__title {
  font-size: 16px;
  font-weight: 600;
}

.chat-home__total {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.chat-home__items {
  flex: 1;
  overflow-y: auto;
}

/* ------------------------------ 全局搜索 ------------------------------ */
.chat-home__search {
  flex: none;
  padding: 0 12px 8px;
}

.chat-home__search-results {
  flex: 1;
  overflow-y: auto;
}

.gsearch {
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--im-border);
}

.gsearch:hover {
  background: var(--im-hover, #ebebeb);
}

.gsearch__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.gsearch__conv {
  font-size: 13px;
  font-weight: 600;
  color: var(--im-primary);
}

.gsearch__time {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.gsearch__body {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.gsearch__sender {
  color: var(--im-text);
}

.gsearch__content :deep(mark) {
  background: #fff3cd;
  padding: 0 2px;
  border-radius: 2px;
}

.gsearch__more {
  padding: 6px 0;
  text-align: center;
}

.gsearch__empty {
  padding: 24px 0;
  text-align: center;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.conv {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
}

.conv:hover {
  background: var(--im-hover, #ebebeb);
}

.conv--active,
.conv--active:hover {
  background: var(--im-active, #e0e0e0);
}

/* 置顶会话在左上角留一道主色标记，比加图标更省横向空间 */
.conv--top::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 3px;
  height: 100%;
  background: var(--im-primary);
}

.conv__body {
  flex: 1;
  min-width: 0;
}

.conv__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conv__row + .conv__row {
  margin-top: 4px;
}

.conv__name {
  font-size: 14px;
  color: var(--im-text);
}

.conv__time {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.conv__summary {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.conv__at {
  color: #f56c6c;
}

.conv__flags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.conv__flag {
  font-size: 13px;
  color: var(--im-text-secondary);
}

/* el-badge 在窄行里默认会溢出到行外，收一下定位 */
.conv__flags :deep(.el-badge__content) {
  border: none;
}

.chat-home__main {
  flex: 1;
  min-width: 0;
  background: var(--im-chat-bg);
}

.chat-home__placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

/* ------------------------------ 窄屏：单栏切换 ------------------------------ */
@media (max-width: 768px) {
  /*
   * 一次只看一栏：没选会话时全屏列表，选了之后全屏聊天。
   * 两栏各占一半的话，300px 的列表和剩下的聊天区都没法用。
   * 切换靠根节点上的 chat-home--chatting，而不是改路由结构。
   */
  .chat-home__aside {
    width: 100%;
    flex: 1;
    border-right: none;
  }

  .chat-home__main {
    display: none;
  }

  .chat-home--chatting .chat-home__aside {
    display: none;
  }

  .chat-home--chatting .chat-home__main {
    display: block;
    flex: 1;
  }
}
</style>
