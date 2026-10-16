<template>
  <el-dialog
    v-model="dialogVisible"
    :title="title || '聊天记录'"
    width="440px"
    append-to-body
    @closed="onClosed"
  >
    <div class="merged-viewer__list im-scroll">
      <div v-for="(item, idx) in messages" :key="idx" class="merged-viewer__item">
        <div class="merged-viewer__head">
          <span class="merged-viewer__nick im-ellipsis">{{ item.nickname || '' }}</span>
          <span class="merged-viewer__time">{{ formatTime(item.time) }}</span>
        </div>
        <div class="merged-viewer__body">{{ displaySummary(item) }}</div>
      </div>
      <el-empty v-if="!messages.length" description="没有可展示的记录" :image-size="60" />
    </div>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 合并转发（聊天记录）查看器。
 *
 * 只渲染发送时定格的快照：昵称 + 一句摘要 + 时间。附件类不还原像素，按类型给占位文字，
 * 这与后端 MERGED_FORWARD 的设计一致（合并的是发送者当时可见的视图，不做回表取原文）。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  messages: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:visible'])

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const TYPE_PLACEHOLDER = {
  2: '[图片]',
  3: '[文件]',
  4: '[语音]',
  6: '[通话]',
  7: '[名片]',
  8: '[位置]',
  9: '[聊天记录]'
}

function displaySummary(item) {
  const type = Number(item.msgType)
  if (TYPE_PLACEHOLDER[type] && (!item.summary || item.summary === TYPE_PLACEHOLDER[type])) {
    return TYPE_PLACEHOLDER[type]
  }
  // 附件仍可能带文件名/地址等文字，直接展示；缺失时按类型兜底占位
  if (item.summary) {
    return item.summary
  }
  return TYPE_PLACEHOLDER[type] || ''
}

function formatTime(time) {
  if (!time) return ''
  const date = new Date(time)
  return Number.isNaN(date.getTime()) ? String(time) : date.toLocaleString()
}

function onClosed() {
  // 无内部状态需要清理，保留钩子位与 ForwardDialog 风格一致
}
</script>

<style scoped>
.merged-viewer__list {
  max-height: 60vh;
  overflow-y: auto;
}

.merged-viewer__item {
  padding: 8px 0;
  border-bottom: 1px solid var(--im-border, #ebeef5);
}

.merged-viewer__item:last-child {
  border-bottom: none;
}

.merged-viewer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.merged-viewer__nick {
  font-size: 13px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.merged-viewer__time {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary, #909399);
}

.merged-viewer__body {
  margin-top: 3px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--im-text, #303133);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
