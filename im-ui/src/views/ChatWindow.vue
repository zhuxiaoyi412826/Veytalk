<template>
  <div class="chat-window">
    <!-- ==================== 头部 ==================== -->
    <header class="chat-window__header">
      <!-- 窄屏下聊天窗口全屏，这是回到会话列表的唯一入口；宽屏有左侧列表，按钮隐藏 -->
      <el-button class="chat-window__back" text :icon="ArrowLeft" @click="router.push({ name: 'chat' })" />
      <div class="chat-window__heading">
        <span class="chat-window__name im-ellipsis">{{ title }}</span>
        <span v-if="subtitle" class="chat-window__subtitle">{{ subtitle }}</span>
      </div>
      <div class="chat-window__actions">
        <el-tooltip v-if="peerUserId" content="查看资料" placement="bottom">
          <el-button text :icon="User" @click="openProfile" />
        </el-tooltip>
        <el-tooltip content="搜索消息" placement="bottom">
          <el-button text :icon="Search" @click="toggleSearch" />
        </el-tooltip>
        <el-tooltip v-if="isGroup" content="群设置" placement="bottom">
          <el-button text :icon="Setting" @click="showGroupSettings = true" />
        </el-tooltip>
        <el-tooltip content="全部标记已读" placement="bottom">
          <el-button text :icon="Select" @click="markAllRead" />
        </el-tooltip>
        <el-tooltip content="刷新消息" placement="bottom">
          <el-button text :icon="Refresh" :loading="loading" @click="reload" />
        </el-tooltip>
      </div>
    </header>

    <!-- ==================== 搜索栏 ==================== -->
    <div v-if="searchVisible" class="chat-window__search">
      <el-input
        v-model.trim="searchKeyword"
        placeholder="搜索消息内容"
        clearable
        :prefix-icon="Search"
        @clear="closeSearch"
        @keyup.enter="doSearch"
      />
      <el-button text size="small" @click="closeSearch">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>

    <!-- 搜索结果 -->
    <div v-if="searching" class="chat-window__search-results im-scroll">
      <div class="chat-window__search-header">
        <span>搜索结果（{{ searchResults.length }}{{ searchHasMore ? '+' : '' }}）</span>
        <el-button v-if="searchHasMore" link type="primary" :loading="searchLoading" @click="loadMoreSearch">
          加载更多
        </el-button>
      </div>
      <div
        v-for="item in searchResults"
        :key="item.messageId"
        class="chat-window__search-item"
      >
        <span class="chat-window__search-sender">{{ item.fromNickname }}</span>
        <span class="chat-window__search-content" v-html="highlightSearch(item.content)"></span>
      </div>
      <div v-if="!searchResults.length && !searchLoading" class="chat-window__search-empty">
        未找到匹配的消息
      </div>
    </div>

    <!-- ==================== 消息区 ==================== -->
    <div ref="scrollRef" class="chat-window__body im-scroll" @scroll.passive="onScroll">
      <div v-if="hasMore" class="chat-window__more">
        <el-button link type="primary" :loading="loadingMore" @click="loadMore">查看更早的消息</el-button>
      </div>
      <div v-else-if="messages.length" class="chat-window__more chat-window__more--end">没有更早的消息了</div>

      <template v-for="(item, index) in messages" :key="item.messageId || item.clientMsgId">
        <div v-if="showDivider(index)" class="chat-window__divider">{{ formatMsgTime(item.sendTime) }}</div>
        <div :data-msg-id="item.messageId ? asId(item.messageId) : undefined">
          <MessageBubble
            :message="item"
            :show-sender="isGroup"
            :is-group="isGroup"
            @menu="(event) => onBubbleMenu(event, item)"
            @resend="onResend"
            @discard="onDiscard"
            @view-file="onViewFile"
            @jump-quote="onJumpQuote"
          />
        </div>
      </template>

      <div v-if="!messages.length && !loading" class="chat-window__empty">
        <el-empty description="还没有消息，发一条打个招呼吧" :image-size="80" />
      </div>
    </div>

    <!-- 滚上去看历史时来了新消息，不强行拽回底部，改成给一个入口 -->
    <transition name="el-fade-in">
      <div v-if="showJump" class="chat-window__jump" @click="jumpToBottom">
        <el-icon><ArrowDown /></el-icon>
        <span>新消息</span>
      </div>
    </transition>

    <!-- ==================== 输入区 ==================== -->
    <footer class="chat-window__input">
      <div class="chat-window__toolbar">
        <el-popover
          v-model:visible="emojiVisible"
          placement="top-start"
          trigger="click"
          :width="360"
          :show-arrow="false"
        >
          <template #reference>
            <el-button text :icon="Sunny" title="表情" />
          </template>
          <EmojiPicker @select="onEmoji" />
        </el-popover>

        <el-tooltip v-if="canUpload" content="发送图片" placement="top">
          <el-button text :icon="Picture" :disabled="uploading" @click="pickImage" />
        </el-tooltip>
        <el-tooltip v-if="canUpload" content="发送文件（音频会作为语音消息）" placement="top">
          <el-button text :icon="FolderOpened" :disabled="uploading" @click="pickFile" />
        </el-tooltip>

        <span
          v-if="uploading"
          class="chat-window__progress"
          :title="uploadFileName"
        >
          <el-progress
            :percentage="uploadPercent"
            :stroke-width="4"
            :show-text="false"
            :status="uploadStage === 'instant' || uploadStage === 'done' ? 'success' : undefined"
            style="width: 120px"
          />
          <span class="chat-window__progress-text">{{ uploadStageText }}</span>
        </span>
      </div>

      <!-- 被禁言时的提示 -->
      <div v-if="isMuted" class="chat-window__muted-notice">
        {{ isMuteAll ? '群聊已开启全员禁言，仅管理员与群主可发言' : '你已被禁言，无法发送消息' }}
      </div>

      <!-- 回复横幅：选定了要回复的消息后在输入框上方展示，点右侧 X 取消 -->
      <div v-if="replyTarget" class="chat-window__reply-banner">
        <div class="chat-window__reply-info">
          <span class="chat-window__reply-label">回复 {{ replyTarget.fromNickname }}</span>
          <span class="chat-window__reply-content im-ellipsis">{{ replyPreview }}</span>
        </div>
        <el-button text :icon="Close" size="small" @click="cancelReply" />
      </div>

      <el-input
        ref="inputRef"
        v-model="draft"
        type="textarea"
        :rows="isMobile ? 1 : 2"
        resize="none"
        :maxlength="MAX_TEXT_LENGTH"
        :show-word-limit="!isMobile"
        :disabled="isMuted"
        :placeholder="inputPlaceholder"
        @keydown.enter="onEnter"
      />

      <!-- @ 提及选择器 -->
      <el-popover
        :visible="mentionVisible"
        placement="top-start"
        :width="240"
        :show-arrow="false"
      >
        <template #reference>
          <span />
        </template>
        <div class="chat-window__mention-list im-scroll">
          <div
            v-if="isGroup"
            class="chat-window__mention-item"
            @mousedown.prevent="selectMention({ userId: 0, displayName: '所有人' })"
          >
            @所有人
          </div>
          <div
            v-for="m in mentionMembers"
            :key="m.userId"
            class="chat-window__mention-item"
            @mousedown.prevent="selectMention(m)"
          >
            {{ m.nicknameInGroup || m.displayName || m.nickname }}
          </div>
          <div v-if="!mentionMembers.length && !isGroup" class="chat-window__mention-empty">
            暂无群成员
          </div>
        </div>
      </el-popover>

      <div class="chat-window__send-row">
        <el-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="sendText">发送</el-button>
      </div>

      <!--
        用原生 input 而不是 el-upload：el-upload 内部走自己的 XHR，
        不经过 api/request.js 那个实例，satoken 头、统一错误提示、1002 跳登录
        全都要再配一遍。这里只需要「选一个文件」，原生 input 反而更直接。
      -->
      <input ref="imageInputRef" type="file" accept="image/*" class="chat-window__file-input" @change="onPicked" />
      <input ref="fileInputRef" type="file" class="chat-window__file-input" @change="onPicked" />
    </footer>

    <ContextMenu
      v-model:visible="menu.visible"
      :x="menu.x"
      :y="menu.y"
      :items="menuItems"
      @select="onMenuSelect"
    />

    <FileViewer
      v-model:visible="viewer.visible"
      :file-url="viewer.fileUrl"
      :file-name="viewer.fileName"
    />

    <ForwardDialog
      v-model:visible="forwardDialog.visible"
      :message-id="forwardDialog.messageId"
    />

    <GroupSettings
      v-if="isGroup"
      v-model="showGroupSettings"
      :group-id="groupId"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowLeft, Close, FolderOpened, Picture, Refresh, Search, Select, Setting, Sunny, User } from '@element-plus/icons-vue'
import MessageBubble from '@/components/MessageBubble.vue'
import EmojiPicker from '@/components/EmojiPicker.vue'
import ContextMenu from '@/components/ContextMenu.vue'
import FileViewer from '@/components/FileViewer.vue'
import GroupSettings from '@/components/GroupSettings.vue'
import ForwardDialog from '@/components/ForwardDialog.vue'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { useGroupStore, ROLE_ADMIN, ROLE_OWNER } from '@/stores/group'
import { uploadFileSmart } from '@/api/file'
import { searchMessages } from '@/api/message'
import { downloadFile, readAudioDuration, readImageSize, readVideoMetadata, isVideo } from '@/utils/media'
// TODO: 视频压缩功能待后续开发 —— 客户端 ffmpeg.wasm 压缩后上传，减少带宽消耗
// import { compressVideo, isFFmpegAvailable } from '@/utils/video-compressor'
import { formatFileSize, formatMsgTime, needTimeDivider } from '@/utils/format'
import { asId, sameId } from '@/utils/id'

defineOptions({ name: 'ChatWindow' })

const props = defineProps({
  conversationId: { type: [String, Number], required: true }
})

/** 后端 im.message.max-text-length，超了会被拒；在输入框上就拦住比发出去再报错好 */
const MAX_TEXT_LENGTH = 5000

/** 前端选文件的总大小上限，对齐后端分片通道 im.file.upload.max-size（2GB）。
 *  ≤5MB 走直传（受 spring.servlet.multipart.max-file-size=100MB 约束，远未触及）；
 *  >5MB 自动走分片上传，边切边传，单个分片请求体才 ~5MB，不受那道 100MB multipart 限制。
 *  要传更大的文件，需同时调大后端 im.file.upload.max-size 与 max-chunks。 */
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024

/** 消息类型与文件业务类型，与后端 MsgType / im-file 的约定对齐 */
const TYPE_IMAGE = 2
const TYPE_FILE = 3
const TYPE_VOICE = 4

const router = useRouter()
const chat = useChatStore()
const conversations = useConversationStore()
const auth = useAuthStore()
const groupStore = useGroupStore()
const settings = useSettingsStore()

/** 是否为窄屏（移动端），用于控制工具栏显隐与输入框行数 */
function checkMobile() {
  if (typeof window === 'undefined') return false
  const w = window.innerWidth
  // 宽度 <= 768 一定是移动端
  if (w <= 768) return true
  // 宽度 <= 1024 且有触摸能力，也视为移动端（平板 / 大屏手机）
  if (w <= 1024 && ('ontouchstart' in window || navigator.maxTouchPoints > 0)) return true
  return false
}
const isMobile = ref(checkMobile())
if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => {
    isMobile.value = checkMobile()
  })
}

const key = computed(() => asId(props.conversationId))

const messages = computed(() => chat.listOf(props.conversationId))
const hasMore = computed(() => chat.hasMoreOf(props.conversationId))
const loadingMore = computed(() => chat.isLoadingOf(props.conversationId))

/* ------------------------------ 会话信息 ------------------------------ */

/**
 * 列表里没有这个会话时的兜底详情。
 *
 * 直接刷新到 /chat/{id} 或者点开一个刚被隐藏的会话时会走到这里，
 * 此时 store 的列表还没加载完，头部不能空着。
 */
const detail = ref(null)

const conv = computed(() => conversations.find(props.conversationId) || detail.value)

const isGroup = computed(() => Number(conv.value?.type) === 2)
const peerUserId = computed(() => (Number(conv.value?.type) === 1 ? conv.value.targetId : null))

/** 群聊时 targetId 即为群 ID，供群设置抽屉与 @ 提及使用 */
const groupId = computed(() => (isGroup.value ? conv.value?.targetId : null))

/**
 * 当前用户在群聊中是否被禁言。
 * myMuted 为单人禁言，muteAll 为全员禁言（管理员以上不受影响，但此处仅做展示提示）。
 */
const isMuted = computed(() => {
  if (!isGroup.value) return false
  const g = groupStore.currentGroup
  return !!(g && (g.myMuted || g.muteAll))
})
const isMuteAll = computed(() => {
  if (!isGroup.value) return false
  return !!groupStore.currentGroup?.muteAll
})

const title = computed(() => conv.value?.name || '会话')

const subtitle = computed(() => {
  const target = conv.value
  if (!target) {
    return ''
  }
  if (isGroup.value) {
    return target.memberCount ? `${target.memberCount} 人` : ''
  }
  // 群聊的 online 后端固定给 false，只有单聊这个字段才有意义
  return target.online ? '在线' : '离线'
})

function openProfile() {
  if (peerUserId.value) {
    router.push({ name: 'user-profile', params: { id: asId(peerUserId.value) } })
  }
}

/* -------------------------------- 滚动 -------------------------------- */

const scrollRef = ref(null)
const showJump = ref(false)
const loading = ref(false)

/**
 * 是否贴着底部。
 *
 * 新消息到达时只有贴底才自动滚：用户正往上翻历史时被拽回底部，
 * 会直接丢掉他正在看的位置，这是聊天界面最招骂的行为之一。
 */
let nearBottom = true

function isNearBottom() {
  const el = scrollRef.value
  if (!el) {
    return true
  }
  return el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom(smooth = false) {
  nextTick(() => {
    const el = scrollRef.value
    if (!el) {
      return
    }
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    nearBottom = true
    showJump.value = false
  })
}

function jumpToBottom() {
  scrollToBottom(true)
}

function onScroll() {
  nearBottom = isNearBottom()
  if (nearBottom) {
    showJump.value = false
  }
  const el = scrollRef.value
  // 触顶自动加载上一页。store 里的 loadingHistory 会挡掉并发，
  // 所以这里不必再做节流，惯性滚动连续触发也只会真正发一次请求；
  // 关闭「自动加载历史」后，触顶不再拉取，用户仍可用顶部按钮手动加载
  if (settings.autoLoadHistory && el && el.scrollTop < 40 && hasMore.value && !loadingMore.value) {
    loadMore()
  }
}

/**
 * 加载更早的一页，并保持视觉位置不动。
 *
 * 前插内容会把 scrollTop 的语义整体下移，不补偿的话用户眼前的消息会突然跳走。
 * 补偿量就是「新增内容的高度」= 新的 scrollHeight - 旧的 scrollHeight。
 */
async function loadMore() {
  const el = scrollRef.value
  if (!el) {
    return
  }
  const prevHeight = el.scrollHeight
  const prevTop = el.scrollTop
  await chat.loadHistory(props.conversationId, false)
  await nextTick()
  el.scrollTop = prevTop + (el.scrollHeight - prevHeight)
  // 位置是人为设回去的，别让下一次 onScroll 误判成「用户滚到了顶部」而再拉一页
  nearBottom = isNearBottom()
}

/** 时间分隔线：与上一条间隔够久才显示，否则每条消息上都挂一个时间戳；关闭「时间戳显示」后一律不显示 */
function showDivider(index) {
  if (!settings.showTimestamp) {
    return false
  }
  return needTimeDivider(index > 0 ? messages.value[index - 1] : null, messages.value[index])
}

/** 消息条数变化时决定是否跟随滚动 */
watch(
  () => messages.value.length,
  (length, previous) => {
    if (length <= (previous || 0)) {
      return
    }
    const last = messages.value[length - 1]
    // 自己发的消息无条件滚到底：用户刚按下发送，一定要看到它出去了
    if (last && last.self) {
      scrollToBottom(true)
      return
    }
    if (nearBottom) {
      scrollToBottom(true)
    } else {
      showJump.value = true
    }
  }
)

/* ------------------------------ 数据加载 ------------------------------ */

async function loadInitial() {
  loading.value = true
  try {
    // 头部信息与历史消息互不依赖，并发拉
    const [, detailVo] = await Promise.all([
      chat.loadHistory(props.conversationId, true),
      conversations.ensureLoaded(props.conversationId)
    ])
    if (detailVo && !conversations.find(props.conversationId)) {
      detail.value = detailVo
    }
  } finally {
    loading.value = false
  }
  scrollToBottom()
  markAllRead()
  // 群聊时拉取群详情，获取禁言状态与角色信息
  if (isGroup.value && groupId.value) {
    groupStore.fetchDetail(groupId.value)
  }
}

async function reload() {
  await loadInitial()
  ElMessage.success('已刷新')
}

/* -------------------------------- 已读 -------------------------------- */

/**
 * 把当前会话标记为已读，并给对方发已读回执。
 *
 * 位点取列表里最后一条有 seq 的消息：本地发送中的占位没有 seq，
 * 拿它当位点会把 ack 位置推到一个服务端不存在的值上。
 */
function markAllRead() {
  const list = messages.value
  let maxSeq = null
  for (let i = list.length - 1; i >= 0; i--) {
    const seq = Number(list[i].seq)
    if (Number.isFinite(seq) && seq > 0) {
      maxSeq = seq
      break
    }
  }
  if (maxSeq === null) {
    return
  }
  chat.reportRead(props.conversationId, maxSeq).catch(() => {})
  conversations.markRead(props.conversationId, maxSeq)
}

/**
 * 标签页从后台切回来时补一次已读。
 *
 * WS 分发只在页面可见时才自动标已读（不可见时不该替用户「已读」），
 * 所以回到前台后要由这里补上，否则未读红点会一直挂着。
 */
function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    markAllRead()
  }
}

/* -------------------------------- 发送 -------------------------------- */

const inputRef = ref(null)
const sending = ref(false)
const emojiVisible = ref(false)

/** 输入框占位提示跟随发送快捷键设置变化，让用户一眼知道当前怎么发送 */
const inputPlaceholder = computed(() => {
  if (isMuted.value) {
    return '你已被禁言'
  }
  return settings.sendKey === 'ctrlEnter'
    ? '输入消息，Ctrl + Enter 发送，Enter 换行'
    : '输入消息，Enter 发送，Shift + Enter 换行'
})

/**
 * 草稿按会话暂存。
 *
 * ChatHome 用 :key 让切换会话时本组件重新挂载，挂载即丢失输入内容。
 * 放到模块级的 Map 里，切回来还能看到刚才打到一半的话。
 * 刻意不放 localStorage：草稿是即时状态，重启浏览器后还留着一堆半句话反而奇怪。
 */
const drafts = new Map()
const draft = ref(drafts.get(key.value) || '')

watch(draft, (value) => {
  if (value) {
    drafts.set(key.value, value)
  } else {
    drafts.delete(key.value)
  }
})

/**
 * Enter 发送。
 *
 * 不能用模板上的 .prevent 修饰符：中文输入法按回车是「确认候选词」，
 * 那时 isComposing 为真，一律拦下来会把还没上屏的拼音当成消息发出去。
 */
function onEnter(event) {
  if (event.isComposing || event.keyCode === 229) {
    return
  }
  // Ctrl+Enter 模式：只有按住 Ctrl（或 Mac 的 Cmd）才发送，普通回车照常换行
  if (settings.sendKey === 'ctrlEnter') {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault()
      sendText()
    }
    return
  }
  if (event.shiftKey) {
    // Shift + Enter 换行，交给浏览器默认行为
    return
  }
  event.preventDefault()
  sendText()
}

async function sendText() {
  const text = draft.value.trim()
  if (!text || sending.value) {
    return
  }
  // 先解析 @ 提及再清输入框，否则 parseMentions 拿到的是空串
  const { text: cleanText, atUserIds, atAll } = parseMentions(text)
  // 引用消息：发送前先拿到 quoteMsgId 再清空，发送失败时 replyTarget 已经清空，
  // 用户重新点回复才能再引用，避免失败消息带者一个看不见的引用关系
  const quoteMsgId = replyTarget.value?.messageId || undefined
  replyTarget.value = null
  // 先清输入框再发：失败时内容还在气泡里（带「重发」入口），
  // 留在输入框反而会让用户以为没发出去而再按一次，制造重复消息
  draft.value = ''
  sending.value = true
  try {
    await chat.send(props.conversationId, {
      msgType: 1,
      content: cleanText,
      atUserIds: atUserIds.length ? atUserIds : undefined,
      atAll: atAll || undefined,
      quoteMsgId
    })
  } catch {
    // 占位消息已被 store 标成「发送失败」，错误提示也由 request.js 弹过了
  } finally {
    sending.value = false
  }
}

function onEmoji(emoji) {
  draft.value += emoji
  // 面板保持打开，连续挑几个表情更顺手；焦点还给输入框以便接着打字
  nextTick(() => inputRef.value?.focus())
}

/* -------------------------------- 消息搜索 -------------------------------- */

const searchVisible = ref(false)
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const searchHasMore = ref(false)
const searching = ref(false)
let searchTimer = null

function toggleSearch() {
  searchVisible.value = !searchVisible.value
  if (!searchVisible.value) {
    closeSearch()
  }
}

function closeSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchVisible.value = false
  searchKeyword.value = ''
  searchResults.value = []
  searching.value = false
  searchHasMore.value = false
}

/** 300ms 防抖搜索，后端要求必须指定 conversationId */
watch(searchKeyword, (val) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (!val) {
    searchResults.value = []
    searching.value = false
    searchHasMore.value = false
    return
  }
  searchTimer = setTimeout(doSearch, 300)
})

async function doSearch() {
  if (!searchKeyword.value) return
  searchLoading.value = true
  try {
    const page = await searchMessages({
      conversationId: props.conversationId,
      keyword: searchKeyword.value,
      current: 1,
      size: 20
    })
    searchResults.value = (page && page.records) || []
    searchHasMore.value = searchResults.value.length >= 20
    searching.value = true
  } catch {
    // request.js 已处理
  } finally {
    searchLoading.value = false
  }
}

async function loadMoreSearch() {
  if (!searchKeyword.value || searchLoading.value) return
  searchLoading.value = true
  try {
    const nextPage = Math.ceil(searchResults.value.length / 20) + 1
    const page = await searchMessages({
      conversationId: props.conversationId,
      keyword: searchKeyword.value,
      current: nextPage,
      size: 20
    })
    const records = (page && page.records) || []
    searchResults.value.push(...records)
    searchHasMore.value = records.length >= 20
  } catch {
    // request.js 已处理
  } finally {
    searchLoading.value = false
  }
}

/** 搜索结果关键字高亮，转义 HTML 后替换关键字为 <mark> 标签 */
function highlightSearch(text) {
  if (!text) return ''
  const escaped = text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
  const kw = searchKeyword.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(new RegExp(kw, 'gi'), (match) => `<mark>${match}</mark>`)
}

/* -------------------------------- @ 提醒 -------------------------------- */

const mentionVisible = ref(false)
const mentionQuery = ref('')

/** 按输入过滤的群成员列表，排除自己 */
const mentionMembers = computed(() => {
  if (!isGroup.value || !groupId.value) return []
  const members = groupStore.members || []
  const kw = mentionQuery.value.toLowerCase()
  return members.filter((m) => {
    if (sameId(m.userId, auth.userId)) return false
    if (!kw) return true
    const name = (m.nicknameInGroup || m.displayName || m.nickname || '').toLowerCase()
    return name.includes(kw)
  })
})

/**
 * 监听输入框内容，检测 @ 符号以弹出成员选择器。
 *
 * 取最后一个 @ 之后的文本作为过滤关键字，遇到空白字符则关闭选择器。
 */
watch(draft, (val) => {
  if (!isGroup.value) {
    mentionVisible.value = false
    return
  }
  const lastAt = val.lastIndexOf('@')
  if (lastAt < 0) {
    mentionVisible.value = false
    return
  }
  const afterAt = val.slice(lastAt + 1)
  // @ 后已有空白或换行，说明这段文本不再是昵称的一部分
  if (/\s/.test(afterAt)) {
    mentionVisible.value = false
    return
  }
  mentionQuery.value = afterAt
  mentionVisible.value = true
})

function selectMention(member) {
  const lastAt = draft.value.lastIndexOf('@')
  if (lastAt < 0) return
  const isAll = member.userId === 0 || member.userId === '0'
  const name = isAll ? '所有人 ' : `${member.nicknameInGroup || member.displayName || member.nickname} `
  draft.value = draft.value.slice(0, lastAt) + '@' + name
  mentionVisible.value = false
  mentionQuery.value = ''
  nextTick(() => inputRef.value?.focus())
}

/**
 * 解析草稿中的 @提及，返回清洗后的文本与 atUserIds / atAll。
 *
 * 扫描所有 @xxx 模式，在群成员列表中查找匹配项；匹配不到的保留原文但不出现在 atUserIds 中。
 */
function parseMentions(text) {
  const atUserIds = []
  let atAll = false
  const members = groupStore.members || []
  const regex = /@(\S+)/g
  let match
  while ((match = regex.exec(text)) !== null) {
    const name = match[1]
    if (name === '所有人') {
      atAll = true
      continue
    }
    const found = members.find((m) => {
      const mname = m.nicknameInGroup || m.displayName || m.nickname || ''
      return mname === name
    })
    if (found && !sameId(found.userId, auth.userId)) {
      atUserIds.push(found.userId)
    }
  }
  return { text, atUserIds, atAll }
}

/* -------------------------------- 上传 -------------------------------- */

const imageInputRef = ref(null)
const fileInputRef = ref(null)
const uploading = ref(false)
const uploadPercent = ref(0)
/** 上传阶段：hash 计算文件中 / upload 传分片 / merge 服务端合并 / instant 秒传 / done 完成 */
const uploadStage = ref('upload')
/** 分片计数 { loaded, total }，仅 upload 阶段有值，用于展示「已传 x/y 片」 */
const uploadChunkInfo = ref(null)
/** 正在上传的文件名，挂在进度提示的 title 上 */
const uploadFileName = ref('')

/**
 * 把上传阶段翻译成人话：分片上传时额外带上分片进度，秒传/合并阶段没有百分比意义直接用文案覆盖。
 */
const uploadStageText = computed(() => {
  const p = uploadPercent.value
  switch (uploadStage.value) {
    case 'hash':
      return `计算文件中 ${p}%`
    case 'instant':
      return '秒传完成'
    case 'merge':
      return '合并中…'
    case 'done':
      return '上传完成'
    case 'upload': {
      const info = uploadChunkInfo.value
      return info ? `上传中 ${info.loaded}/${info.total} 片 · ${p}%` : `上传中 ${p}%`
    }
    default:
      return `上传中 ${p}%`
  }
})

/** 没有上传权限就把入口藏掉：种子数据里 user 角色是有 file:upload 的，管理员同样有 */
const canUpload = computed(() => auth.hasPermission('file:upload'))

function pickImage() {
  imageInputRef.value?.click()
}

function pickFile() {
  fileInputRef.value?.click()
}

/**
 * 选完文件后统一走这里。
 *
 * input 的 value 必须清空：连续两次选同一个文件时，
 * 值没变则 change 事件不触发，表现为「第二次点没反应」。
 */
async function onPicked(event) {
  const input = event.target
  const file = input.files && input.files[0]
  input.value = ''
  if (!file) {
    return
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    ElMessage.error(`文件不能超过 ${formatFileSize(MAX_UPLOAD_BYTES)}，当前 ${formatFileSize(file.size)}`)
    return
  }
  await uploadAndSend(file)
}

async function uploadAndSend(file) {
  const kind = kindOf(file)
  uploading.value = true
  uploadPercent.value = 0
  uploadStage.value = 'upload'
  uploadChunkInfo.value = null
  uploadFileName.value = file.name || ''
  try {
    const extra = {}
    let duration = null
    if (kind === 'image') {
      // 宽高服务端不回填，只能上传前自己读；读不到就让它自然撑开
      const size = await readImageSize(file)
      if (size) {
        extra.width = size.width
        extra.height = size.height
      }
    } else if (kind === 'voice') {
      duration = await readAudioDuration(file)
      if (duration) {
        extra.duration = duration
      }
    } else if (kind === 'video') {
      // 视频：读取元数据（时长、宽高），直接上传原始文件
      // TODO: 后续开发客户端 ffmpeg.wasm 压缩功能，压缩后再上传以节省带宽
      const meta = await readVideoMetadata(file)
      if (meta) {
        if (meta.duration) { extra.duration = meta.duration }
        if (meta.width) { extra.width = meta.width }
        if (meta.height) { extra.height = meta.height }
      }
    }

    const bizType = kind === 'image' ? 'chat_image' : kind === 'voice' ? 'chat_voice' : 'chat_file'
    const vo = await uploadFileSmart(file, bizType, duration, (percent, stage, chunkInfo) => {
      uploadPercent.value = percent
      if (stage) {
        uploadStage.value = stage
      }
      uploadChunkInfo.value = chunkInfo || null
    })

    // 这里传的 fileName / fileSize / fileUrl 会被服务端按文件记录覆盖（防越权改写），
    // 但仍然要传：本地占位气泡要靠它在服务端响应回来之前就把内容渲染出来
    await chat.sendAttachment(props.conversationId, {
      msgType: kind === 'image' ? TYPE_IMAGE : kind === 'voice' ? TYPE_VOICE : TYPE_FILE,
      fileId: vo.fileId,
      extra: {
        ...extra,
        fileName: vo.originalName,
        fileSize: vo.size,
        contentType: vo.contentType,
        ext: vo.ext,
        fileUrl: vo.url
      }
    })
  } catch {
    // 上传失败或发送失败都已由 request.js / store 处理，这里只负责收尾
  } finally {
    uploading.value = false
    uploadPercent.value = 0
    uploadStage.value = 'upload'
    uploadChunkInfo.value = null
    uploadFileName.value = ''
  }
}

/**
 * 按 MIME 判断消息类型。
 *
 * 只看扩展名不可靠（.png 改名 .txt 很常见），而 type 是浏览器按内容嗅探出来的。
 * type 为空时（少数系统上会发生）退化成扩展名判断，最后兜底为普通文件。
 */
function kindOf(file) {
  const type = file.type || ''
  if (type.startsWith('image/')) {
    return 'image'
  }
  if (type.startsWith('audio/')) {
    return 'voice'
  }
  if (type.startsWith('video/')) {
    return 'video'
  }
  if (!type) {
    const name = (file.name || '').toLowerCase()
    if (/\.(png|jpe?g|gif|webp|bmp)$/.test(name)) {
      return 'image'
    }
    if (/\.(mp3|wav|m4a|aac|ogg|flac|amr)$/.test(name)) {
      return 'voice'
    }
    if (isVideoFile(file)) {
      return 'video'
    }
  }
  return 'file'
}

/** 按 MIME 或扩展名判断是否为视频文件 */
function isVideoFile(file) {
  if (file.type && file.type.startsWith('video/')) {
    return true
  }
  return isVideo(file.name)
}

/* ------------------------------ 文件预览 ------------------------------ */

const viewer = reactive({ visible: false, fileUrl: '', fileName: '' })

function onViewFile({ fileUrl, fileName }) {
  viewer.fileUrl = fileUrl
  viewer.fileName = fileName
  viewer.visible = true
}

/* ------------------------------ 右键菜单 ------------------------------ */

const menu = reactive({ visible: false, x: 0, y: 0, target: null })

/**
 * 当前正在回复的消息，不为空时在输入框上方展示回复横幅。
 * 发送后清空，切换会话时也要清空（否则上一条会话的回复对象会泄露到新会话里）。
 */
const replyTarget = ref(null)

/** 转发对话框状态 */
const forwardDialog = reactive({ visible: false, messageId: null })

/**
 * 回复横幅里的内容摘要，与气泡里的引用块保持一致的口径。
 */
const replyPreview = computed(() => {
  const msg = replyTarget.value
  if (!msg) return ''
  if (msg.recalled) return '[消息已撤回]'
  const type = Number(msg.msgType)
  if (type === 2) return '[图片]'
  if (type === 3) return `[文件] ${msg.extra?.fileName || ''}`
  if (type === 4) return '[语音]'
  return msg.content || ''
})

function cancelReply() {
  replyTarget.value = null
}

/**
 * 点击气泡里的引用块时跳转到原消息。
 *
 * 当前列表里能找到就滚过去并短暂高亮；
 * 找不到（历史分页尚未加载到）时提示用户向上滚动。
 */
function onJumpQuote(quoteMsgId) {
  if (!quoteMsgId) return
  const el = document.querySelector(`[data-msg-id="${asId(quoteMsgId)}"]`)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.add('chat-window__msg--highlight')
    setTimeout(() => el.classList.remove('chat-window__msg--highlight'), 1600)
  } else {
    ElMessage.info('原消息尚未加载，请向上滚动查看历史消息')
  }
}

/** 撤回时限，与后端 im.message.recall-limit-seconds 保持一致（2 小时） */
const RECALL_LIMIT_MS = 2 * 60 * 60 * 1000

/**
 * 消息是否还在可撤回的时间窗口内。
 *
 * 客户端时钟可能有偏差，这里只做「隐藏入口」的软判断，
 * 最终时限仍由后端 recall 接口裁决（超时会返回 MESSAGE_RECALL_TIMEOUT）。
 */
function withinRecallWindow(message) {
  const sent = new Date(message.sendTime).getTime()
  if (Number.isNaN(sent)) {
    return false
  }
  return Date.now() - sent <= RECALL_LIMIT_MS
}

function itemsFor(message) {
  if (!message || !message.messageId) {
    // 还没落库的本地占位（发送中 / 发送失败）没有可撤回、可删除的对象，
    // 失败的补救入口直接画在气泡下面，不需要菜单
    return []
  }
  const items = [
    { key: 'copy', label: '复制', show: Number(message.msgType) === 1 && !message.recalled },
    {
      key: 'reply',
      label: '回复',
      // 已撤回的消息不能回复；自己发的消息也没必要回复自己
      show: !message.recalled && !message.self
    },
    {
      key: 'forward',
      label: '转发',
      show: !message.recalled
    },
    {
      key: 'recall',
      label: '撤回',
      // 撤回别人的消息需要 message:recall:any 权限（群主/管理员/运营），普通用户只能撤自己的；
      // 且发送超过 2 小时后不再提供入口，与后端时限保持一致
      show: !message.recalled
        && (message.self || auth.hasPermission('message:recall:any'))
        && withinRecallWindow(message)
    },
    { key: 'download', label: '另存为', show: [TYPE_IMAGE, TYPE_FILE, TYPE_VOICE].includes(Number(message.msgType)) },
    // 单端删除：只从自己的记录里移除，对方仍可见；自己发的和对方发的都能删
    { key: 'delete', label: '删除', danger: true, show: true }
  ]
  return items.filter((item) => item.show)
}

const menuItems = computed(() => itemsFor(menu.target))

function onBubbleMenu(event, message) {
  const items = itemsFor(message)
  if (!items.length) {
    return
  }
  menu.target = message
  menu.x = event.clientX
  menu.y = event.clientY
  menu.visible = true
}

async function onMenuSelect(action) {
  const message = menu.target
  if (!message) {
    return
  }
  try {
    switch (action) {
      case 'copy':
        await copyText(message.content || '')
        break
      case 'reply':
        replyTarget.value = message
        // 回复时自动把焦点拉回输入框，省得用户再点一次
        nextTick(() => inputRef.value?.focus())
        break
      case 'forward':
        forwardDialog.messageId = message.messageId
        forwardDialog.visible = true
        break
      case 'recall':
        await chat.recall(props.conversationId, message.messageId)
        ElMessage.success('已撤回')
        break
      case 'download':
        await onDownload(message)
        break
      case 'delete':
        await confirmDelete(message)
        break
      default:
        break
    }
  } catch {
    // 提示已由下层弹出
  }
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    // 非 HTTPS 且非 localhost 时 clipboard API 不可用，退回老办法
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    if (ok) {
      ElMessage.success('已复制')
    } else {
      ElMessage.warning('当前浏览器不允许自动复制，请手动选择文本')
    }
  }
}

async function onDownload(message) {
  const url = message.extra?.fileUrl
  if (!url) {
    ElMessage.warning('文件地址缺失，无法保存')
    return
  }
  const name = message.extra?.fileName || `message-${asId(message.messageId)}`
  try {
    await downloadFile(url, name)
  } catch {
    // 换取直链失败（如登录态失效）已由 request.js 弹过提示，这里只兜住异常避免未捕获拒绝
  }
}

/**
 * 删除消息。
 *
 * 后端的删除是「单端删除」：只对自己不可见，对方那边还在。
 * 提示语要把这一点讲明白，否则用户会以为消息从双方都消失了。
 */
async function confirmDelete(message) {
  try {
    await ElMessageBox.confirm('删除后这条消息只从你的记录中消失，对方仍然可以看到。', '删除消息', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await chat.remove(props.conversationId, message.messageId)
  ElMessage.success('已删除')
}

/* ------------------------------ 失败重发 ------------------------------ */

async function onResend(message) {
  try {
    await chat.resend(props.conversationId, message)
  } catch {
    // 已标记为失败并弹过提示
  }
}

function onDiscard(message) {
  chat.discard(props.conversationId, message.clientMsgId)
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  await loadInitial()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  // 离开会话时把已经看到的消息标成已读，避免回到列表还挂着红点
  if (document.visibilityState === 'visible') {
    markAllRead()
  }
})

// 切换会话时 ChatHome 用 :key 强制重挂载，所以这里不需要监听 conversationId；
// 万一将来去掉了 :key，靠这个 watcher 也能正确重新加载
watch(key, async () => {
  detail.value = null
  draft.value = drafts.get(key.value) || ''
  replyTarget.value = null
  nearBottom = true
  showJump.value = false
  await loadInitial()
})
</script>

<style scoped>
.chat-window {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.chat-window__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  flex: none;
  padding: 0 12px 0 16px;
  background: var(--im-panel);
  border-bottom: 1px solid var(--im-border);
}

.chat-window__back {
  display: none;
}

.chat-window__heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.chat-window__name {
  font-size: 15px;
  font-weight: 600;
}

.chat-window__subtitle {
  flex: none;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.chat-window__actions {
  display: flex;
  align-items: center;
  flex: none;
}

.chat-window__body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0 12px;
}

.chat-window__more {
  padding: 6px 0;
  text-align: center;
  font-size: 12px;
}

.chat-window__more--end {
  color: var(--im-text-secondary);
}

.chat-window__divider {
  margin: 10px auto 6px;
  padding: 2px 10px;
  width: fit-content;
  font-size: 11px;
  color: #ffffff;
  background: #dadada;
  border-radius: 3px;
}

.chat-window__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.chat-window__jump {
  position: absolute;
  right: 24px;
  bottom: 190px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  font-size: 12px;
  color: var(--im-primary);
  background: var(--im-panel);
  border: 1px solid var(--im-primary);
  border-radius: 14px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}

.chat-window__input {
  flex: none;
  padding: 6px 12px 10px;
  background: var(--im-panel);
  border-top: 1px solid var(--im-border);
}

.chat-window__toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 34px;
}

.chat-window__progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
}

.chat-window__progress-text {
  font-size: 12px;
  color: var(--im-text-secondary);
  white-space: nowrap;
}

.chat-window__send-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

/* 原生 input 只是用来弹出选择框，不参与布局 */
.chat-window__file-input {
  display: none;
}

/* 输入框去掉边框，聊天场景里外层已经有分隔线了 */
.chat-window__input :deep(.el-textarea__inner) {
  box-shadow: none;
  padding: 4px 2px;
}

.chat-window__input :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.chat-window__input :deep(.el-input__count) {
  right: 4px;
  bottom: 2px;
  background: transparent;
}

/* ------------------------------ 搜索栏 ------------------------------ */
.chat-window__search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  flex: none;
  background: var(--im-panel);
  border-bottom: 1px solid var(--im-border);
}

.chat-window__search-results {
  flex: none;
  max-height: 200px;
  overflow-y: auto;
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid var(--im-border);
}

.chat-window__search-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-bottom: 6px;
}

.chat-window__search-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--im-border);
}

.chat-window__search-item:last-child {
  border-bottom: none;
}

.chat-window__search-sender {
  flex: none;
  font-size: 12px;
  color: var(--im-primary);
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-window__search-content {
  flex: 1;
  min-width: 0;
  color: var(--im-text);
  word-break: break-all;
}

.chat-window__search-content :deep(mark) {
  background: #fff3cd;
  padding: 0 2px;
  border-radius: 2px;
}

.chat-window__search-empty {
  padding: 12px 0;
  text-align: center;
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* ------------------------------ 禁言提示 ------------------------------ */
.chat-window__muted-notice {
  padding: 6px 12px;
  font-size: 12px;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 4px;
  margin-bottom: 4px;
}

/* ------------------------------ 回复横幅 ------------------------------ */
.chat-window__reply-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  margin-bottom: 4px;
  background: var(--im-bg, #f5f5f5);
  border-left: 3px solid var(--im-primary, #409eff);
  border-radius: 0 4px 4px 0;
}

.chat-window__reply-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.chat-window__reply-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--im-primary, #409eff);
}

.chat-window__reply-content {
  font-size: 12px;
  color: var(--im-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ------------------------------ 跳转高亮 ------------------------------ */
.chat-window__msg--highlight :deep(.bubble__box) {
  animation: quote-flash 1.4s ease-out;
}

@keyframes quote-flash {
  0%, 30% { box-shadow: 0 0 0 3px var(--im-primary, #409eff); }
  100%    { box-shadow: 0 0 0 0 transparent; }
}

/* ------------------------------ @ 提及 ------------------------------ */
.chat-window__mention-list {
  max-height: 200px;
  overflow-y: auto;
}

.chat-window__mention-item {
  padding: 6px 10px;
  font-size: 13px;
  cursor: pointer;
}

.chat-window__mention-item:hover {
  background: #f5f5f5;
}

.chat-window__mention-empty {
  padding: 10px;
  text-align: center;
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* ------------------------------ 窄屏 ------------------------------ */
@media (max-width: 768px) {
  .chat-window__back {
    display: inline-flex;
    margin-right: 4px;
  }

  .chat-window__header {
    padding-left: 4px;
  }

  .chat-window__jump {
    right: 12px;
  }

  /* 移动端输入区紧凑布局 */
  .chat-window__input {
    padding: 4px 8px 6px;
  }

  .chat-window__input :deep(.el-textarea__inner) {
    padding: 6px 4px !important;
  }
}
</style>
