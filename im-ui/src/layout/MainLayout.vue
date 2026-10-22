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
        <!-- 直播入口：跳转到直播大厅（房间列表 + 开播面板）；
             退出登录挪到「设置」页的「账号」卡片，窄屏底栏格子宝贵，不再为它留位 -->
        <div
          class="main-layout__item"
          :class="{ 'main-layout__item--active': isActive({ match: 'live' }) }"
          title="直播"
          @click="router.push({ name: 'live' })"
        >
          <el-icon :size="22"><VideoCamera /></el-icon>
          <span class="main-layout__label">直播</span>
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

    <!-- 全局通话浮层：与 useCallStore 联动，切会话/翻页都不打断通话 -->
    <CallOverlay />

    <!--
      通话悬浮小窗：浮层点「最小化」后由它接管（可拖动、点一下回到浮层）。
      两者靠 store 的 minimized 交接：浮层只是用 opacity 抹掉（不卸载，卸载会把声音出口搞断），两路流一直在跑。
    -->
    <CallFloatWindow />

    <!--
      群通话浮层：同理挂在布局而不是聊天页——mesh 的 N-1 路 PeerConnection 存在 store 里，
      路由切换不会把它们拆掉；两个浮层 z-index 相同（都在同一层级），但前端已拦住了
      同时开 1:1 与群通话（见 ws/dispatch.js 的跨类型拦截），正常情况下不会叠在一起。
    -->
    <GroupCallOverlay />

    <!--
      举报弹窗：全站只挂这一份。入口有四个（消息右键、用户资料卡、直播间、群设置），
      各引一个组件会让「同时开着两个举报窗」成为可能；
      它们统一调 report.openReport({ targetType, targetId, title })，由 store 驱动这一个实例。
    -->
    <ReportDialog />
  </div>
</template>

<script setup>
import { computed, onBeforeMount, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Microphone, Monitor, Setting, User, VideoCamera } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import CallOverlay from '@/components/CallOverlay.vue'
import CallFloatWindow from '@/components/CallFloatWindow.vue'
import GroupCallOverlay from '@/components/GroupCallOverlay.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useConversationStore } from '@/stores/conversation'
import { useFriendStore } from '@/stores/friend'
import { useChatStore } from '@/stores/chat'
import { useReportStore } from '@/stores/report'
import { installWsDispatch, uninstallWsDispatch } from '@/ws/dispatch'
import { socketState } from '@/ws/socket'

defineOptions({ name: 'MainLayout' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const conversation = useConversationStore()
const friend = useFriendStore()
const chat = useChatStore()
const report = useReportStore()

/**
 * 导航项。
 *
 * 「申请」不再占独立导航项：好友页顶部已有带红点的「申请」入口，
 * 侧边栏再放一个是重复占用宝贵的一格位置（尤其窄屏底栏）。
 * 待处理红点由好友页内那个入口承接。
 * badge 用 computed 求值，保持响应式。
 */
const navItems = computed(() => [
  { name: 'chat', label: '消息', icon: ChatDotRound, badge: conversation.totalUnread, match: 'chat' },
  { name: 'friends', label: '好友', icon: User, badge: 0, match: 'friends' },
  // 后端 Java 全栈面试：基于知识库 RAG 的模拟面试，无未读概念，badge 恒为 0
  { name: 'interview', label: '面试', icon: Microphone, badge: 0, match: 'interview' },
  // 远程控制：被控设备清单与远程会话入口，同样无未读概念
  { name: 'remote', label: '远程', icon: Monitor, badge: 0, match: 'remote' },
  // 齿轮图标不变，但入口由「我的（个人信息）」改为「设置（全局页面设置）」；
  // 个人信息仍由顶部头像进入，窄屏下头像隐藏时改由设置页内的入口兼顾
  { name: 'settings', label: '设置', icon: Setting, badge: 0, match: 'settings' }
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
    chat.pullOffline(),
    // 举报字典与开关：四个入口都靠它决定显不显示，取一次比每处各取一次好
    report.ensureConfig()
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
  document.addEventListener('dragover', blockRawFileDrop)
  document.addEventListener('drop', blockRawFileDrop)
})

onBeforeUnmount(() => {
  uninstallWsDispatch()
  document.removeEventListener('dragover', blockRawFileDrop)
  document.removeEventListener('drop', blockRawFileDrop)
})

/**
 * 兜底：拦掉落在聊天区之外的文件拖放。
 *
 * 浏览器的默认行为是「拖放文件即导航到该文件」，会把整个 SPA 换成一张本地图片，
 * 用户只能刷新回来。聊天窗自己接管了 drop，其余位置（会话列表/好友/设置）统一在这里挡掉。
 */
function blockRawFileDrop(event) {
  const types = event.dataTransfer && event.dataTransfer.types
  if (types && Array.from(types).includes('Files')) {
    event.preventDefault()
  }
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

  /*
   * 窄屏隐藏头像入口。个人信息页改由「设置」页顶部的「编辑个人信息」按钮进入，
   * 因此这里去掉头像不会让 profile 在移动端失去入口。
   */
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
