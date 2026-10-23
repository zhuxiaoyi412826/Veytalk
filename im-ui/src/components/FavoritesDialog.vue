<template>
  <el-dialog
    v-model="visible"
    title="我的收藏"
    width="640px"
    top="8vh"
    :close-on-click-modal="true"
    append-to-body
    @open="onOpen"
  >
    <!-- 顶部工具条：只看当前会话的开关 + 总数 -->
    <div class="fav__bar">
      <el-checkbox v-model="onlyThisConv" :disabled="!currentConversationId" @change="onFilterChange">
        只看当前会话
      </el-checkbox>
      <span class="fav__count">共 {{ total }} 条</span>
    </div>

    <div v-loading="loading" class="fav__body im-scroll">
      <div v-if="!list.length && !loading" class="fav__empty">
        <el-empty description="还没有收藏的消息，聊天时右键任意消息即可收藏" :image-size="80" />
      </div>
      <div v-else class="fav__list">
        <div
          v-for="item in list"
          :key="item.messageId"
          class="fav__item"
          @click="onJump(item)"
        >
          <div class="fav__head">
            <span class="fav__conv im-ellipsis" :title="convNameOf(item.conversationId)">
              {{ convNameOf(item.conversationId) }}
            </span>
            <span class="fav__time">{{ formatDateTime(item.favoritedAt || item.sendTime) }}</span>
          </div>
          <div class="fav__sub">
            <span class="fav__sender im-ellipsis">{{ item.fromNickname || '我' }}</span>
            <span class="fav__text">{{ previewOf(item) }}</span>
          </div>
          <div class="fav__ops">
            <!-- 收藏时间与被收藏消息的时间不同：正文那行才是消息本身，右侧小字是「我什么时候收的」 -->
            <span class="fav__sent">原消息 {{ formatDateTime(item.sendTime) }}</span>
            <el-button
              size="small"
              text
              type="warning"
              :loading="busyId === asId(item.messageId)"
              @click.stop="onUnfavorite(item)"
            >取消收藏</el-button>
          </div>
        </div>
      </div>

      <div v-if="list.length && list.length < total" class="fav__more">
        <el-button link type="primary" :loading="loading" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { fetchFavorites } from '@/api/message'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'
import { asId, sameId } from '@/utils/id'
import { formatDateTime } from '@/utils/format'

/**
 * 我的收藏面板。
 *
 * 数据来自 GET /api/message/favorites：后端按 im_message_favorite 的 create_time 倒序分页，
 * conversationId 传空即跨全部会话，所以这里既能当「全量收藏夹」，也能勾成「本会话的收藏」。
 *
 * 点击整行是跳转：先写 chat.locateTarget，再 router.push 到那条消息所属会话。
 * ChatWindow 挂载时会消费这个目标，必要时向上补拉历史（封顶 10 页），补到就闪一下高亮。
 * 补不到只提示手动滚动 —— 收藏的消息可能非常早，不值得为它把整个会话拉穿。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 当前打开的会话 ID：用于「只看当前会话」过滤，为空时该勾选项禁用 */
  currentConversationId: { type: [String, Number], default: null }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const router = useRouter()
const chat = useChatStore()
const conversation = useConversationStore()

const list = ref([])
const total = ref(0)
const current = ref(1)
const loading = ref(false)
const onlyThisConv = ref(false)
/** 正在取消收藏的那条消息 ID，用于给对应按钮单独上 loading */
const busyId = ref(null)
const PAGE_SIZE = 20

/** 附件类消息没有可读正文，按类型占位，与群精华面板的口径一致 */
const TYPE_PREVIEW = {
  2: '[图片]',
  3: '[文件]',
  4: '[语音]',
  6: '[通话]',
  7: '[名片]',
  8: '[位置]',
  9: '[聊天记录]',
  10: '[投票]',
  12: '[转账]',
  13: '[红包]'
}

function previewOf(item) {
  const type = Number(item.msgType)
  if (type === 3) {
    const name = item.extra?.fileName
    return name ? `[文件] ${name}` : '[文件]'
  }
  if (TYPE_PREVIEW[type]) {
    return TYPE_PREVIEW[type]
  }
  return item.content || ''
}

function convNameOf(conversationId) {
  return conversation.find(conversationId)?.name || '会话'
}

async function load(reset) {
  loading.value = true
  try {
    const page = reset ? 1 : current.value + 1
    const conversationId = onlyThisConv.value ? props.currentConversationId : null
    const res = await fetchFavorites({ conversationId, current: page, size: PAGE_SIZE })
    const records = (res && res.records) || []
    list.value = reset ? records : list.value.concat(records)
    current.value = page
    total.value = (res && res.total) || 0
  } catch (e) {
    // 传输层错误已由全局 toast 提示；收藏表缺失时后端会返回系统异常，这里补一句可操作的提示
    ElMessage.error(e?.message || '收藏列表加载失败')
  } finally {
    loading.value = false
  }
}

function loadMore() {
  load(false)
}

function onOpen() {
  list.value = []
  total.value = 0
  current.value = 1
  load(true)
}

function onFilterChange() {
  load(true)
}

/** 取消收藏：成功后就地移除该行，总数跟着减一，不用重拉整页 */
async function onUnfavorite(item) {
  const id = asId(item.messageId)
  busyId.value = id
  try {
    await chat.markFavorite(item.conversationId, item.messageId, false)
    list.value = list.value.filter((row) => !sameId(row.messageId, item.messageId))
    total.value = Math.max(0, total.value - 1)
    ElMessage.success('已取消收藏')
  } catch {
    // 失败提示已由下层弹出，列表保持不动
  } finally {
    busyId.value = null
  }
}

/** 跳转到那条消息所在的会话，并请求 ChatWindow 定位到它 */
function onJump(item) {
  const conversationId = asId(item.conversationId)
  if (!conversationId) {
    ElMessage.info('该消息的会话已不存在')
    return
  }
  chat.setLocateTarget(conversationId, item.messageId)
  visible.value = false
  router.push({ name: 'chat', params: { conversationId } })
}
</script>

<style scoped>
.fav__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.fav__count {
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.fav__body {
  max-height: 60vh;
  overflow-y: auto;
}

.fav__empty {
  padding: 20px 0;
}

.fav__list {
  display: flex;
  flex-direction: column;
}

.fav__item {
  padding: 10px 8px;
  border-bottom: 1px solid var(--im-border-light, #f0f0f0);
  cursor: pointer;
}

.fav__item:hover {
  background: var(--im-bg-hover, #f5f7fa);
}

.fav__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.fav__conv {
  font-size: 13px;
  font-weight: 600;
  color: #d4380d;
  min-width: 0;
}

.fav__time {
  flex: none;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.fav__sub {
  display: flex;
  gap: 6px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--im-text, #303133);
  word-break: break-word;
}

.fav__sender {
  flex: none;
  max-width: 30%;
  color: var(--im-text-secondary, #909399);
}

.fav__text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fav__ops {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.fav__sent {
  font-size: 12px;
  color: var(--im-text-placeholder, #b1b3b8);
}

.fav__more {
  padding: 12px 0;
  text-align: center;
}
</style>
