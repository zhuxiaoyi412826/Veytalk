<template>
  <div
    class="debug-log"
    :class="{ 'debug-log--collapsed': collapsed }"
    :style="{ bottom: posBottom + 'px', right: posRight + 'px' }"
  >
    <!-- 标题栏：可拖拽 -->
    <div class="debug-log__header" @mousedown="startDrag">
      <span class="debug-log__title">Debug Log ({{ buffer.length }})</span>
      <button type="button" class="debug-log__btn" @click="collapsed = !collapsed">
        {{ collapsed ? '▲' : '▼' }}
      </button>
      <button type="button" class="debug-log__btn" @click="buffer = []">清</button>
    </div>
    <!-- 日志列表 -->
    <div v-show="!collapsed" ref="listRef" class="debug-log__list im-scroll">
      <div v-for="(entry, i) in buffer" :key="i" class="debug-log__entry">
        <span class="debug-log__time">{{ formatTime(entry.timestamp) }}</span>
        <span class="debug-log__level" :class="'debug-log__level--' + entry.level">{{ entry.level }}</span>
        <span class="debug-log__msg">{{ entry.message }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { onLog } from '@/utils/logger'

const MAX_BUFFER = 200

const buffer = ref([])
const collapsed = ref(false)
const listRef = ref(null)

// 位置（右下角偏上，不遮挡系统托盘）
const posBottom = ref(60)
const posRight = ref(16)

// 拖拽状态
let dragging = false
let startX = 0
let startY = 0
let startRight = 0
let startBottom = 0

function startDrag(e) {
  dragging = true
  startX = e.clientX
  startY = e.clientY
  startRight = posRight.value
  startBottom = posBottom.value
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
}

function onDrag(e) {
  if (!dragging) return
  posRight.value = Math.max(0, startRight - (e.clientX - startX))
  posBottom.value = Math.max(0, startBottom + (e.clientY - startY))
}

function stopDrag() {
  dragging = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
}

let unsubscribe = null
onMounted(() => {
  unsubscribe = onLog((entry) => {
    buffer.value.push(entry)
    if (buffer.value.length > MAX_BUFFER) {
      buffer.value.splice(0, buffer.value.length - MAX_BUFFER)
    }
    // 自动滚到底
    nextTick(() => {
      if (listRef.value) {
        listRef.value.scrollTop = listRef.value.scrollHeight
      }
    })
  })
})

onUnmounted(() => {
  if (unsubscribe) unsubscribe()
})

function formatTime(ts) {
  const d = new Date(ts)
  return d.toLocaleTimeString('zh-CN', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0')
}
</script>

<style scoped>
.debug-log {
  position: fixed;
  z-index: 99999;
  width: 480px;
  max-width: 90vw;
  background: rgba(24, 24, 24, 0.92);
  border-radius: 8px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.3);
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 11px;
  color: #e0e0e0;
  user-select: text;
}

.debug-log--collapsed {
  width: auto;
}

.debug-log__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: move;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.debug-log__title {
  flex: 1;
  font-weight: 600;
  white-space: nowrap;
}

.debug-log__btn {
  border: none;
  background: rgba(255, 255, 255, 0.1);
  color: #ccc;
  padding: 2px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
}

.debug-log__btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.debug-log__list {
  max-height: 260px;
  overflow-y: auto;
  padding: 4px 10px 8px;
}

.debug-log__entry {
  display: flex;
  gap: 6px;
  padding: 2px 0;
  line-height: 1.5;
}

.debug-log__time {
  flex-shrink: 0;
  color: #6a9955;
}

.debug-log__level {
  flex-shrink: 0;
  text-transform: uppercase;
  font-weight: 600;
}

.debug-log__level--debug {
  color: #569cd6;
}

.debug-log__level--warn {
  color: #ce9178;
}

.debug-log__level--error {
  color: #f44747;
}

.debug-log__msg {
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
