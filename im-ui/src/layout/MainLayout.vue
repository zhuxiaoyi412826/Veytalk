<template>
  <div class="main-layout">
    <nav class="main-layout__nav">
      <div class="main-layout__me" title="个人中心" @click="router.push({ name: 'profile' })">
        <UserAvatar :src="auth.avatarRaw" :name="auth.nickname" :size="38" />
        <span class="main-layout__nickname im-ellipsis">{{ auth.nickname }}</span>
      </div>

      <div class="main-layout__items">
        <div
          v-for="item in navItems"
          :key="item.name"
          class="main-layout__item"
          :class="{ 'main-layout__item--active': isActive(item) }"
          :title="item.label"
          @click="router.push({ name: item.name })"
        >
          <el-badge
            :value="item.badge"
            :max="99"
            :hidden="!item.badge"
            class="main-layout__badge"
          >
            <el-icon :size="22"><component :is="item.icon" /></el-icon>
          </el-badge>
          <span class="main-layout__label">{{ item.label }}</span>
        </div>
      </div>

      <div class="main-layout__footer">
        <el-tooltip :content="connectionText" placement="right">
          <div class="main-layout__status">
            <span
              class="im-online-dot main-layout__dot"
              :class="{ 'im-offline-dot': !connected }"
            ></span>
          </div>
        </el-tooltip>
        <div class="main-layout__item" title="退出登录" @click="onLogout">
          <el-icon :size="22"><SwitchButton /></el-icon>
          <span class="main-layout__label">退出</span>
        </div>
      </div>
    </nav>

    <main class="main-layout__body">
      <router-view v-slot="{ Component }">
        <!--
          keep-alive 只缓存会话列表页：从好友页点「发消息」跳过去时，
          列表的滚动位置与已加载数据能留住。
          聊天窗口不缓存，它需要按路由参数重新绑定会话。
        -->
        <keep-alive :include="['ChatHome']">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeMount, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, ChatDotRound, Setting, SwitchButton, User } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { useConversationStore } from '@/stores/conversation'
import { useFriendStore } from '@/stores/friend'
import { useChatStore } from '@/stores/chat'
import { installWsDispatch, uninstallWsDispatch } from '@/ws/dispatch'
import { signOut } from '@/stores'
import { socketState } from '@/ws/socket'

defineOptions({ name: 'MainLayout' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const conversation = useConversationStore()
const friend = useFriendStore()
const chat = useChatStore()

/**
 * 导航项。
 *
 * 「申请」单独占一项而不是塞进好友页：它是唯一需要用户及时处理的东西，
 * 藏一层会让红点失去意义。
 * badge 用 computed 求值，保持响应式。
 */
const navItems = computed(() => [
  { name: 'chat', label: '消息', icon: ChatDotRound, badge: conversation.totalUnread, match: 'chat' },
  { name: 'friends', label: '好友', icon: User, badge: 0, match: 'friends' },
  { name: 'friend-requests', label: '申请', icon: Bell, badge: friend.pendingCount, match: 'friend-requests' },
  { name: 'profile', label: '我的', icon: Setting, badge: 0, match: 'profile' }
])

/**
 * 高亮判定用 match 而不是直接比 name：/chat/123 的 route.name 仍是 chat，
 * 但 /user/123 不属于任何导航项，此时应当全部不高亮。
 */
function isActive(item) {
  return route.name === item.match
}

const connected = computed(() => socketState.status === 'open')

const connectionText = computed(() => {
  switch (socketState.status) {
    case 'open':
      return '实时连接正常'
    case 'connecting':
      return '正在连接服务器…'
    case 'reconnecting':
      return '连接已断开，正在重连…'
    case 'closed':
      return '实时连接已关闭，消息将在刷新后同步'
    default:
      return '实时连接未建立'
  }
})

/**
 * 首屏数据加载。
 *
 * 放在 onBeforeMount 而不是 onMounted：父组件的 beforeMount 早于子组件挂载，
 * 这样 ChatHome 挂载时 conversation.loading 已经是 true，
 * 它就能靠这个标记判断「有人在拉了」而不再重复发一次列表请求。
 */
async function bootstrap() {
  const tasks = [
    conversation.fetchList(),
    friend.fetchFriends(),
    friend.refreshPendingCount(),
    // 离线消息在这里拉一次：WS 的 open 分发只在「重连」时拉，首次登录不会触发
    chat.pullOffline()
  ]
  if (!auth.resolved) {
    tasks.push(auth.loadCurrentUser())
  }
  // 四个请求互不依赖，任何一个失败都不该阻塞其余的界面渲染，
  // 所以用 allSettled —— request.js 已经弹过提示，这里只负责不让 Promise 链断掉
  await Promise.allSettled(tasks)
  // 离线消息可能带来新会话与新未读，再对一次服务端真值
  conversation.refreshTotalUnread()
}

onBeforeMount(() => {
  // 先装分发再拉数据：装分发内部会建立连接，
  // 若顺序反过来，连接建立到列表拉完之间到达的推送会因为找不到会话而被丢弃
  installWsDispatch()
  bootstrap()
})

onBeforeUnmount(() => {
  uninstallWsDispatch()
})

async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能收发消息，确定退出？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  // 清理步骤集中在 stores/index.js 的 signOut，与「我的」页面那个退出入口共用一份
  await signOut()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}
</script>

<style scoped>
.main-layout {
  display: flex;
  height: 100%;
  background: var(--im-bg);
}

.main-layout__nav {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: var(--im-nav-width);
  flex: none;
  padding: 12px 0;
  background: #2e3238;
  color: #dcdee0;
}

/*
 * body 必须显式 flex: 1：flex 子项默认不伸展，宽度会退化成内容的 max-content，
 * 表现为宽屏下右侧留一大片空白——会话列表加聊天窗口只有内容本身那么宽。
 * min-width: 0 同理，否则长内容会把 body 撑出横向滚动。
 */
.main-layout__body {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.main-layout__me {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.main-layout__nickname {
  max-width: 56px;
  font-size: 11px;
  line-height: 14px;
  color: #a8abb2;
}

.main-layout__items {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 20px;
  flex: 1;
}

.main-layout__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 52px;
  padding: 6px 0;
  border-radius: 6px;
  cursor: pointer;
  color: #a8abb2;
}

.main-layout__item:hover {
  color: #ffffff;
}

.main-layout__item--active {
  color: var(--im-primary);
}

.main-layout__label {
  font-size: 11px;
  line-height: 14px;
}

.main-layout__footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.main-layout__status {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 18px;
  cursor: default;
}

/* 导航条是深色底，全局绿点的白色描边在这里会显得突兀，改成深色描边 */
.main-layout__dot {
  position: static;
  border-color: #2e3238;
}

/* el-badge 的默认位置会把数字压到图标上，导航项是竖排的，往右上挪一点 */
.main-layout__badge :deep(.el-badge__content) {
  top: 4px;
  right: 10px;
  border: none;
}

/* ------------------------------ 窄屏：导航条沉底 ------------------------------ */
@media (max-width: 768px) {
  .main-layout {
    flex-direction: column;
  }

  /* 竖排导航条横过来放到底部：用 order 调顺序，模板不用动 */
  .main-layout__nav {
    order: 1;
    flex-direction: row;
    align-items: center;
    width: 100%;
    height: 56px;
    padding: 0 2px;
  }

  /* 头像入口与「我的」导航项功能重复，窄屏只留后者 */
  .main-layout__me {
    display: none;
  }

  .main-layout__items {
    flex-direction: row;
    justify-content: space-around;
    margin-top: 0;
    gap: 0;
  }

  .main-layout__item {
    width: 58px;
  }

  .main-layout__footer {
    flex-direction: row;
    align-items: center;
    gap: 0;
    padding-right: 2px;
  }

  .main-layout__status {
    width: 18px;
    height: auto;
  }

  /* 横排后徽章的默认偏移会压到相邻图标上，收一点 */
  .main-layout__badge :deep(.el-badge__content) {
    top: 2px;
    right: 8px;
  }
}
</style>
