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

      <div v-loading="conversation.loading && conversation.list.length === 0" class="chat-home__items im-scroll">
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
            :size="42"
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
                {{ item.lastMsgContent || '暂无消息' }}
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
    <section class="chat-home__main">
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
import { computed, onMounted, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MuteNotification, Refresh } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ContextMenu from '@/components/ContextMenu.vue'
import ChatWindow from './ChatWindow.vue'
import { useConversationStore } from '@/stores/conversation'
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
 * 删除会话。
 *
 * 后端这个接口等价于「隐藏」：消息本身还在，对方再发新消息时会话会自动回到列表。
 * 提示语必须说清楚这一点，否则用户以为聊天记录被清了，或者以为删了就再也收不到消息。
 */
async function confirmRemove(target) {
  try {
    await ElMessageBox.confirm(
      `将从列表中移除「${target.name}」，聊天记录保留，对方再发消息时会话会重新出现。`,
      '删除会话',
      { confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await conversation.remove(target.conversationId)
  if (sameId(conversation.activeId, target.conversationId)) {
    router.replace({ name: 'chat' })
  }
  ElMessage.success('已从列表移除')
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
  background: #f7f7f7;
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

.conv {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
}

.conv:hover {
  background: #ebebeb;
}

.conv--active,
.conv--active:hover {
  background: #e0e0e0;
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
