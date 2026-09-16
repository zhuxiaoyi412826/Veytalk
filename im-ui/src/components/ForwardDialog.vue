<template>
  <el-dialog
    v-model="dialogVisible"
    title="转发消息"
    width="400px"
    :close-on-click-modal="false"
    append-to-body
    @closed="onClosed"
  >
    <el-input
      v-model="keyword"
      placeholder="搜索会话名称"
      clearable
      :prefix-icon="Search"
      style="margin-bottom: 10px"
    />

    <div class="forward-dialog__list im-scroll">
      <div
        v-for="conv in filteredList"
        :key="conv.conversationId"
        class="forward-dialog__item"
        :class="{ 'forward-dialog__item--selected': isSelected(conv) }"
        @click="toggleSelect(conv)"
      >
        <UserAvatar :src="conv.avatar" :name="conv.name" :size="36" />
        <div class="forward-dialog__info">
          <div class="forward-dialog__name im-ellipsis">{{ conv.name }}</div>
          <div class="forward-dialog__type">{{ Number(conv.type) === 2 ? '群聊' : '单聊' }}</div>
        </div>
        <el-icon v-if="isSelected(conv)" class="forward-dialog__check"><Select /></el-icon>
      </div>
      <el-empty v-if="!filteredList.length" description="暂无匹配的会话" :image-size="60" />
    </div>

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!selected.length"
        @click="doForward"
      >
        转发{{ selected.length ? `（${selected.length}）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Select } from '@element-plus/icons-vue'
import UserAvatar from './UserAvatar.vue'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { asId, sameId } from '@/utils/id'

/**
 * 转发对话框。
 *
 * 支持多选目标会话：用户按住 Ctrl 点选或直接点选，点已选中的取消选择。
 * 提交时按选中顺序依次调用转发接口，任一失败不影响其他目标（全部完成后统一汇报）。
 *
 * 刻意不在这里处理「转发给自己」的情况：后端允许向自己的会话转发（相当于收藏），
 * 前端不做额外限制。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 被转发的消息 ID，为空时对话框不显示 */
  messageId: { type: [String, Number], default: null }
})

const emit = defineEmits(['update:visible', 'forwarded'])

const conversations = useConversationStore()
const chat = useChatStore()

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const keyword = ref('')
const selected = ref([])
const submitting = ref(false)

const filteredList = computed(() => {
  const list = conversations.list || []
  if (!keyword.value) return list
  const kw = keyword.value.toLowerCase()
  return list.filter((conv) => (conv.name || '').toLowerCase().includes(kw))
})

function isSelected(conv) {
  return selected.value.some((id) => sameId(id, conv.conversationId))
}

function toggleSelect(conv) {
  const id = asId(conv.conversationId)
  const idx = selected.value.findIndex((sid) => sameId(sid, id))
  if (idx >= 0) {
    selected.value.splice(idx, 1)
  } else {
    selected.value.push(id)
  }
}

function onClosed() {
  keyword.value = ''
  selected.value = []
  submitting.value = false
}

/**
 * 重置选中项：messageId 变化时清空，避免把上一次的选择带到下一条消息上。
 */
watch(
  () => props.messageId,
  () => {
    selected.value = []
    keyword.value = ''
  }
)

async function doForward() {
  if (!props.messageId || !selected.value.length) return
  submitting.value = true
  let success = 0
  let failed = 0
  for (const conversationId of selected.value) {
    try {
      await chat.forward(conversationId, props.messageId)
      success++
    } catch {
      failed++
    }
  }
  submitting.value = false
  if (failed === 0) {
    ElMessage.success(`已转发到 ${success} 个会话`)
  } else if (success === 0) {
    ElMessage.error('转发失败，请稍后重试')
  } else {
    ElMessage.warning(`已转发到 ${success} 个会话，${failed} 个失败`)
  }
  emit('forwarded')
  dialogVisible.value = false
}
</script>

<style scoped>
.forward-dialog__list {
  max-height: 320px;
  overflow-y: auto;
}

.forward-dialog__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.forward-dialog__item:hover {
  background: var(--im-bg-hover, rgba(0, 0, 0, 0.04));
}

.forward-dialog__item--selected {
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.forward-dialog__info {
  flex: 1;
  min-width: 0;
}

.forward-dialog__name {
  font-size: 14px;
  color: var(--im-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.forward-dialog__type {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-top: 1px;
}

.forward-dialog__check {
  flex: none;
  color: var(--el-color-primary, #409eff);
  font-size: 18px;
}
</style>
